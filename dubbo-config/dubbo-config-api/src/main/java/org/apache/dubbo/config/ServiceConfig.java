/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.config;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.common.threadpool.manager.FrameworkExecutorRepository;
import org.apache.dubbo.common.url.component.ServiceConfigURL;
import org.apache.dubbo.common.utils.ClassUtils;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.annotation.Service;
import org.apache.dubbo.config.invoker.DelegateProviderMetaDataInvoker;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.config.utils.ConfigValidationUtils;
import org.apache.dubbo.metadata.ServiceNameMapping;
import org.apache.dubbo.metrics.event.MetricsEventBus;
import org.apache.dubbo.metrics.registry.event.RegistryEvent;
import org.apache.dubbo.registry.client.metadata.MetadataUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProxyFactory;
import org.apache.dubbo.rpc.ServerService;
import org.apache.dubbo.rpc.cluster.ConfiguratorFactory;
import org.apache.dubbo.rpc.model.ModuleModel;
import org.apache.dubbo.rpc.model.ModuleServiceRepository;
import org.apache.dubbo.rpc.model.ProviderModel;
import org.apache.dubbo.rpc.model.ScopeModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.service.GenericService;

import java.beans.Transient;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_IP_TO_BIND;
import static org.apache.dubbo.common.constants.CommonConstants.EXECUTOR_MANAGEMENT_MODE_ISOLATION;
import static org.apache.dubbo.common.constants.CommonConstants.EXPORTER_LISTENER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LOCALHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MONITOR_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.REVISION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SERVICE_EXECUTOR;
import static org.apache.dubbo.common.constants.CommonConstants.SERVICE_NAME_MAPPING_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.COMMON_ISOLATED_EXECUTOR_CONFIGURATION_ERROR;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_FAILED_EXPORT_SERVICE;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_NO_METHOD_FOUND;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_SERVER_DISCONNECTED;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_UNEXPORT_ERROR;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CONFIG_USE_RANDOM_PORT;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.SERVICE_REGISTRY_PROTOCOL;
import static org.apache.dubbo.common.utils.NetUtils.getAvailablePort;
import static org.apache.dubbo.common.utils.NetUtils.getLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidLocalHost;
import static org.apache.dubbo.common.utils.NetUtils.isInvalidPort;
import static org.apache.dubbo.config.Constants.DUBBO_IP_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_BIND;
import static org.apache.dubbo.config.Constants.DUBBO_PORT_TO_REGISTRY;
import static org.apache.dubbo.config.Constants.SCOPE_NONE;
import static org.apache.dubbo.registry.Constants.REGISTER_KEY;
import static org.apache.dubbo.remoting.Constants.BIND_IP_KEY;
import static org.apache.dubbo.remoting.Constants.BIND_PORT_KEY;
import static org.apache.dubbo.remoting.Constants.IS_PU_SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.GENERIC_KEY;
import static org.apache.dubbo.rpc.Constants.LOCAL_PROTOCOL;
import static org.apache.dubbo.rpc.Constants.PROXY_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_KEY;
import static org.apache.dubbo.rpc.Constants.SCOPE_LOCAL;
import static org.apache.dubbo.rpc.Constants.SCOPE_REMOTE;
import static org.apache.dubbo.rpc.Constants.TOKEN_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;
import static org.apache.dubbo.rpc.support.ProtocolUtils.isGeneric;

public class ServiceConfig<T> extends ServiceConfigBase<T> {

    private static final long serialVersionUID = 7868244018230856253L;

    private static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(ServiceConfig.class);

    /**
     * A random port cache, the different protocols who have no port specified have different random port
     */
    private static final Map<String, Integer> RANDOM_PORT_MAP = new HashMap<String, Integer>();

    private Protocol protocolSPI;

    /**
     * A {@link ProxyFactory} implementation that will generate a exported service proxy,the JavassistProxyFactory is its
     * default implementation
     */
    private ProxyFactory proxyFactory;

    private ProviderModel providerModel;

    /**
     * Whether the provider has been exported
     */
    private transient volatile boolean exported;

    /**
     * The flag whether a service has unexported ,if the method unexported is invoked, the value is true
     */
    private transient volatile boolean unexported;

    private transient volatile AtomicBoolean initialized = new AtomicBoolean(false);

    /**
     * The exported services
     */
    private final List<Exporter<?>> exporters = new ArrayList<Exporter<?>>();

    private final List<ServiceListener> serviceListeners = new ArrayList<>();

    public ServiceConfig() {
    }

    public ServiceConfig(ModuleModel moduleModel) {
        super(moduleModel);
    }

    public ServiceConfig(Service service) {
        super(service);
    }

    public ServiceConfig(ModuleModel moduleModel, Service service) {
        super(moduleModel, service);
    }

    @Override
    protected void postProcessAfterScopeModelChanged(ScopeModel oldScopeModel, ScopeModel newScopeModel) {
        super.postProcessAfterScopeModelChanged(oldScopeModel, newScopeModel);
        protocolSPI = this.getExtensionLoader(Protocol.class).getAdaptiveExtension();
        proxyFactory = this.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    }

    @Override
    @Parameter(excluded = true, attribute = false)
    public boolean isExported() {
        return exported;
    }


    @Override
    @Parameter(excluded = true, attribute = false)
    public boolean isUnexported() {
        return unexported;
    }

    @Override
    public void unexport() {
        if (!exported) {
            return;
        }
        if (unexported) {
            return;
        }
        if (!exporters.isEmpty()) {
            for (Exporter<?> exporter : exporters) {
                try {
                    exporter.unexport();
                } catch (Throwable t) {
                    logger.warn(CONFIG_UNEXPORT_ERROR, "", "", "Unexpected error occurred when unexport " + exporter, t);
                }
            }
            exporters.clear();
        }
        unexported = true;
        onUnexpoted();
        ModuleServiceRepository repository = getScopeModel().getServiceRepository();
        repository.unregisterProvider(providerModel);
    }

    /**
     * for early init serviceMetadata
     * 1.CAS操作
     * 2.通过SPI机制获取服务监听器类型.添加起来
     */
    public void init() {
        // CAS操作
        if (this.initialized.compareAndSet(false, true)) {
            // load ServiceListeners from extension
            // SPI机制
            ExtensionLoader<ServiceListener> extensionLoader = this.getExtensionLoader(ServiceListener.class);
            this.serviceListeners.addAll(extensionLoader.getSupportedExtensionInstances());
        }
        // 初始化服务元数据
        initServiceMetadata(provider);
        // 设置接口类型
        serviceMetadata.setServiceType(getInterfaceClass());
        // 服务接口的实现类
        serviceMetadata.setTarget(getRef());
        // 生成服务的keu
        serviceMetadata.generateServiceKey();
    }

    /**
     * 服务导出 针对的是每个单独的servie,上层通过遍历的方式.
     */
    @Override
    public void export() {
        // 如果服务已经导出, 则直接返回
        if (this.exported) {
            return;
        }

        // ensure start module, compatible with old api usage
        // 启动模块部署器
        getScopeModel().getDeployer().start();

        synchronized (this) {
            // 双检查
            if (this.exported) {
                return;
            }
            // 没有刷新进行刷新
            if (!this.isRefreshed()) {
                this.refresh();
            }
            if (this.shouldExport()) {
                // 服务初始化
                this.init();
                // 不延迟
                if (shouldDelay()) {
                    doDelayExport();
                } else {
                    // 直接导出 ⭐️
                    doExport();
                }
            }
        }
    }

    protected void doDelayExport() {
        ExecutorRepository.getInstance(getScopeModel().getApplicationModel()).getServiceExportExecutor()
            .schedule(() -> {
                try {
                    doExport();
                } catch (Exception e) {
                    logger.error(CONFIG_FAILED_EXPORT_SERVICE, "configuration server disconnected", "", "Failed to (async)export service config: " + interfaceName, e);
                }
            }, getDelay(), TimeUnit.MILLISECONDS);
    }

    protected void exported() {
        exported = true;
        List<URL> exportedURLs = this.getExportedUrls();
        exportedURLs.forEach(url -> {
            if (url.getParameters().containsKey(SERVICE_NAME_MAPPING_KEY)) {
                ServiceNameMapping serviceNameMapping = ServiceNameMapping.getDefaultExtension(getScopeModel());
                ScheduledExecutorService scheduledExecutor = getScopeModel().getBeanFactory()
                    .getBean(FrameworkExecutorRepository.class).getSharedScheduledExecutor();
                mapServiceName(url, serviceNameMapping, scheduledExecutor);
            }
        });
        onExported();
    }

    protected void mapServiceName(URL url, ServiceNameMapping serviceNameMapping, ScheduledExecutorService scheduledExecutor) {
        if (!exported) {
            return;
        }
        logger.info("Try to register interface application mapping for service " + url.getServiceKey());
        boolean succeeded = false;
        try {
            succeeded = serviceNameMapping.map(url);
            if (succeeded) {
                logger.info("Successfully registered interface application mapping for service " + url.getServiceKey());
            } else {
                logger.error(CONFIG_SERVER_DISCONNECTED, "configuration server disconnected", "", "Failed register interface application mapping for service " + url.getServiceKey());
            }
        } catch (Exception e) {
            logger.error(CONFIG_SERVER_DISCONNECTED, "configuration server disconnected", "", "Failed register interface application mapping for service " + url.getServiceKey(), e);
        }
        if (!succeeded && serviceNameMapping.hasValidMetadataCenter()) {
            scheduleToMapping(scheduledExecutor, serviceNameMapping, url);
        }
    }

    private void scheduleToMapping(ScheduledExecutorService scheduledExecutor, ServiceNameMapping serviceNameMapping, URL url) {
        Integer mappingRetryInterval = getApplication().getMappingRetryInterval();
        scheduledExecutor.schedule(() -> mapServiceName(url, serviceNameMapping, scheduledExecutor),
            mappingRetryInterval == null ? 5000 : mappingRetryInterval, TimeUnit.MILLISECONDS);
    }

    private void checkAndUpdateSubConfigs() {

        // Use default configs defined explicitly with global scope
        completeCompoundConfigs();

        checkProtocol();

        // init some null configuration.
        List<ConfigInitializer> configInitializers = this.getExtensionLoader(ConfigInitializer.class)
            .getActivateExtension(URL.valueOf("configInitializer://", getScopeModel()), (String[]) null);
        configInitializers.forEach(e -> e.initServiceConfig(this));

        // if protocol is not injvm checkRegistry
        if (!isOnlyInJvm()) {
            checkRegistry();
        }

        if (StringUtils.isEmpty(interfaceName)) {
            throw new IllegalStateException("<dubbo:service interface=\"\" /> interface not allow null!");
        }

        if (ref instanceof GenericService) {
            interfaceClass = GenericService.class;
            if (StringUtils.isEmpty(generic)) {
                generic = Boolean.TRUE.toString();
            }
        } else {
            try {
                if (getInterfaceClassLoader() != null) {
                    interfaceClass = Class.forName(interfaceName, true, getInterfaceClassLoader());
                } else {
                    interfaceClass = Class.forName(interfaceName, true, Thread.currentThread().getContextClassLoader());
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            checkRef();
            generic = Boolean.FALSE.toString();
        }
        if (local != null) {
            if ("true".equals(local)) {
                local = interfaceName + "Local";
            }
            Class<?> localClass;
            try {
                localClass = ClassUtils.forNameWithThreadContextClassLoader(local);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(localClass)) {
                throw new IllegalStateException("The local implementation class " + localClass.getName() + " not implement interface " + interfaceName);
            }
        }
        if (stub != null) {
            if ("true".equals(stub)) {
                stub = interfaceName + "Stub";
            }
            Class<?> stubClass;
            try {
                stubClass = ClassUtils.forNameWithThreadContextClassLoader(stub);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
            if (!interfaceClass.isAssignableFrom(stubClass)) {
                throw new IllegalStateException("The stub implementation class " + stubClass.getName() + " not implement interface " + interfaceName);
            }
        }
        checkStubAndLocal(interfaceClass);
        ConfigValidationUtils.checkMock(interfaceClass, this);
        ConfigValidationUtils.validateServiceConfig(this);
        postProcessConfig();
    }

    @Override
    protected void postProcessRefresh() {
        super.postProcessRefresh();
        checkAndUpdateSubConfigs();
    }

    protected synchronized void doExport() {
        // 执行过程中 取消导出 直接抛异常
        if (unexported) {
            throw new IllegalStateException("The service " + interfaceClass.getName() + " has already unexported!");
        }
        // 已经导出 直接返回
        if (exported) {
            return;
        }
        // 服务路径是空 直接用接口的全限定类名
        if (StringUtils.isEmpty(path)) {
            path = interfaceName;
        }
        // ⭐️ 导出URL
        doExportUrls();
        // 标记下
        exported();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    // 执行URL的导出
    private void doExportUrls() {
        // 获取范围模型 通过范围模型获取服务仓库
        ModuleServiceRepository repository = getScopeModel().getServiceRepository();
        ServiceDescriptor serviceDescriptor;
        final boolean serverService = ref instanceof ServerService;
        // 是否是服务端服务
        if (serverService) {
            // 获取服务描述符
            serviceDescriptor = ((ServerService) ref).getServiceDescriptor();
            // 通过仓库注册服务 其实就是注册本地的list容器中
            repository.registerService(serviceDescriptor);
        } else {
            // 注册服务
            // 一个serviceConfig.对象应对一个服务接口
            serviceDescriptor = repository.registerService(getInterfaceClass());
        }
        // 创建providerModel
        providerModel = new ProviderModel(serviceMetadata.getServiceKey(), // 服务key
            ref, // 服务接口实现bean
            serviceDescriptor, // 服务描述符
            getScopeModel(), // 范围模型
            serviceMetadata, interfaceClassLoader); // 服务元数据,接口类加载器

        // Compatible with dependencies on ServiceModel#getServiceConfig(), and will be removed in a future version
        // 模型设置当前对象
        providerModel.setConfig(this);

        // 设置销毁任务
        providerModel.setDestroyRunner(getDestroyRunner());
        // 注册providerModel 模型
        repository.registerProvider(providerModel);

        // 加载注册中心的信息
        // 注册中心的URL
        // 可能存在多协议的场景
        // <dubbo:registry address="" protocol="" port="" /> 对应一个条目
        List<URL> registryURLs = ConfigValidationUtils.loadRegistries(this, true);

        // 发布服务注册的事件
        MetricsEventBus.post(RegistryEvent.toRsEvent(module.getApplicationModel(), getUniqueServiceName(), protocols.size() * registryURLs.size()),
            () -> {
                for (ProtocolConfig protocolConfig : protocols) {
                    String pathKey = URL.buildKey(getContextPath(protocolConfig)
                        .map(p -> p + "/" + path)
                        .orElse(path), group, version);
                    // stub service will use generated service name
                    if (!serverService) {
                        // In case user specified path, register service one more time to map it to path.
                        repository.registerService(pathKey, interfaceClass);
                    }
                    // ⭐️  通过协议导出URL  远程注册中心发送注册请求
                    doExportUrlsFor1Protocol(protocolConfig, registryURLs);
                }
                return null;
            }
        );
        // 将服务发布的URL保存在providerModel中
        providerModel.setServiceUrls(urls);
    }

    private void doExportUrlsFor1Protocol(ProtocolConfig protocolConfig, List<URL> registryURLs) {
        // 根据协议配置 构建属性集合
        Map<String, String> map = buildAttributes(protocolConfig);

        // remove null key and null value
        map.keySet().removeIf(key -> StringUtils.isEmpty(key) || StringUtils.isEmpty(map.get(key)));
        // init serviceMetadata attachments
        serviceMetadata.getAttachments().putAll(map);

        // 服务URL
        URL url = buildUrl(protocolConfig, map);
        // 对线程池进行处理 如果线程池模式是"isolation"，则方法返回。
        // 将线程池作为provider模型的属性添加到服务元数据中："service-executor"->线程池
        // 将线程池作为url的属性添加到url的属性集合中："service-executor"->线程池
        processServiceExecutor(url);
        // 单接口协议导出到多个注册中心上
        exportUrl(url, registryURLs);
    }

    private void processServiceExecutor(URL url) {
        if (getExecutor() != null) {
            String mode = application.getExecutorManagementMode();
            if (!EXECUTOR_MANAGEMENT_MODE_ISOLATION.equals(mode)) {
                logger.warn(COMMON_ISOLATED_EXECUTOR_CONFIGURATION_ERROR, "", "", "The current executor management mode is " + mode +
                    ", the configured service executor cannot take effect unless the mode is configured as " + EXECUTOR_MANAGEMENT_MODE_ISOLATION);
                return;
            }
            /**
             * Because executor is not a string type, it cannot be attached to the url parameter, so it is added to URL#attributes
             * and obtained it in IsolationExecutorRepository#createExecutor method
             */
            providerModel.getServiceMetadata().addAttribute(SERVICE_EXECUTOR, getExecutor());
            url.getAttributes().put(SERVICE_EXECUTOR, getExecutor());
        }
    }

    private Map<String, String> buildAttributes(ProtocolConfig protocolConfig) {

        Map<String, String> map = new HashMap<String, String>();
        map.put(SIDE_KEY, PROVIDER_SIDE);

        // append params with basic configs,
        ServiceConfig.appendRuntimeParameters(map);
        AbstractConfig.appendParameters(map, getApplication());
        AbstractConfig.appendParameters(map, getModule());
        // remove 'default.' prefix for configs from ProviderConfig
        // appendParameters(map, provider, Constants.DEFAULT_KEY);
        AbstractConfig.appendParameters(map, provider);
        AbstractConfig.appendParameters(map, protocolConfig);
        AbstractConfig.appendParameters(map, this);
        appendMetricsCompatible(map);

        // append params with method configs,
        if (CollectionUtils.isNotEmpty(getMethods())) {
            getMethods().forEach(method -> appendParametersWithMethod(method, map));
        }

        if (isGeneric(generic)) {
            map.put(GENERIC_KEY, generic);
            map.put(METHODS_KEY, ANY_VALUE);
        } else {
            String revision = Version.getVersion(interfaceClass, version);
            if (StringUtils.isNotEmpty(revision)) {
                map.put(REVISION_KEY, revision);
            }

            String[] methods = methods(interfaceClass);
            if (methods.length == 0) {
                logger.warn(CONFIG_NO_METHOD_FOUND, "", "", "No method found in service interface: " + interfaceClass.getName());
                map.put(METHODS_KEY, ANY_VALUE);
            } else {
                List<String> copyOfMethods = new ArrayList<>(Arrays.asList(methods));
                copyOfMethods.sort(Comparator.naturalOrder());
                map.put(METHODS_KEY, String.join(COMMA_SEPARATOR, copyOfMethods));
            }
        }

        /**
         * Here the token value configured by the provider is used to assign the value to ServiceConfig#token
         */
        if (ConfigUtils.isEmpty(token) && provider != null) {
            token = provider.getToken();
        }

        if (!ConfigUtils.isEmpty(token)) {
            if (ConfigUtils.isDefault(token)) {
                map.put(TOKEN_KEY, UUID.randomUUID().toString());
            } else {
                map.put(TOKEN_KEY, token);
            }
        }

        if (ref instanceof ServerService) {
            map.put(PROXY_KEY, CommonConstants.NATIVE_STUB);
        }

        return map;
    }

    private void appendParametersWithMethod(MethodConfig method, Map<String, String> params) {
        AbstractConfig.appendParameters(params, method, method.getName());

        String retryKey = method.getName() + ".retry";
        if (params.containsKey(retryKey)) {
            String retryValue = params.remove(retryKey);
            if ("false".equals(retryValue)) {
                params.put(method.getName() + ".retries", "0");
            }
        }

        List<ArgumentConfig> arguments = method.getArguments();
        if (CollectionUtils.isNotEmpty(arguments)) {
            Method matchedMethod = findMatchedMethod(method);
            if (matchedMethod != null) {
                arguments.forEach(argument -> appendArgumentConfig(argument, matchedMethod, params));
            }
        }
    }

    private Method findMatchedMethod(MethodConfig methodConfig) {
        for (Method method : interfaceClass.getMethods()) {
            if (method.getName().equals(methodConfig.getName())) {
                return method;
            }
        }
        return null;
    }

    private void appendArgumentConfig(ArgumentConfig argument, Method method, Map<String, String> params) {
        if (StringUtils.isNotEmpty(argument.getType())) {
            Integer index = findArgumentIndexIndexWithGivenType(argument, method);
            AbstractConfig.appendParameters(params, argument, method.getName() + "." + index);
        } else if (hasIndex(argument)) {
            AbstractConfig.appendParameters(params, argument, method.getName() + "." + argument.getIndex());
        } else {
            throw new IllegalArgumentException("Argument config must set index or type attribute.eg: <dubbo:argument index='0' .../> or <dubbo:argument type=xxx .../>");
        }
    }

    private boolean hasIndex(ArgumentConfig argument) {
        return argument.getIndex() != -1;
    }

    private boolean isTypeMatched(String type, Integer index, Class<?>[] argtypes) {
        return index != null && index >= 0 && index < argtypes.length && argtypes[index].getName().equals(type);
    }

    private Integer findArgumentIndexIndexWithGivenType(ArgumentConfig argument, Method method) {
        Class<?>[] argTypes = method.getParameterTypes();
        // one callback in the method
        if (hasIndex(argument)) {
            Integer index = argument.getIndex();
            String type = argument.getType();
            if (isTypeMatched(type, index, argTypes)) {
                return index;
            } else {
                throw new IllegalArgumentException("Argument config error : the index attribute and type attribute not match :index :" + argument.getIndex() + ", type:" + argument.getType());
            }
        } else {
            // multiple callbacks in the method
            for (int j = 0; j < argTypes.length; j++) {
                if (isTypeMatched(argument.getType(), j, argTypes)) {
                    return j;
                }
            }
            throw new IllegalArgumentException("Argument config error : no argument matched with the type:" + argument.getType());
        }
    }

    private URL buildUrl(ProtocolConfig protocolConfig, Map<String, String> params) {
        String name = protocolConfig.getName();
        if (StringUtils.isEmpty(name)) {
            name = DUBBO;
        }

        // export service
        String host = findConfiguredHosts(protocolConfig, provider, params);
        if (NetUtils.isIPV6URLStdFormat(host)) {
            if (!host.contains("[")) {
                host = "[" + host + "]";
            }
        } else if (NetUtils.getLocalHostV6() != null) {
            String ipv6Host = NetUtils.getLocalHostV6();
            params.put(CommonConstants.IPV6_KEY, ipv6Host);
        }

        Integer port = findConfiguredPort(protocolConfig, provider, this.getExtensionLoader(Protocol.class), name, params);
        URL url = new ServiceConfigURL(name, null, null, host, port, getContextPath(protocolConfig).map(p -> p + "/" + path).orElse(path), params);

        // You can customize Configurator to append extra parameters
        if (this.getExtensionLoader(ConfiguratorFactory.class)
            .hasExtension(url.getProtocol())) {
            url = this.getExtensionLoader(ConfiguratorFactory.class)
                .getExtension(url.getProtocol()).getConfigurator(url).configure(url);
        }
        url = url.setScopeModel(getScopeModel());
        url = url.setServiceModel(providerModel);
        return url;
    }

    /**
     * 本方法其实就是通过参数 scope进行决定的,
     * 1.scope = null  会本地导出 也会远程导出
     * 2.scope = local  只本地导出
     * 3.scope = remote  只远程导出
     * @param url
     * @param registryURLs
     */
    private void exportUrl(URL url, List<URL> registryURLs) {
        // 获取url的作用范围
        String scope = url.getParameter(SCOPE_KEY);
        // don't export when none is configured
        if (!SCOPE_NONE.equalsIgnoreCase(scope)) {

            // export to local if the config is not remote (export to remote only when config is remote)
            if (!SCOPE_REMOTE.equalsIgnoreCase(scope)) {
                // 本地导出
                exportLocal(url);
            }

            // export to remote if the config is not local (export to local only when config is local)
            if (!SCOPE_LOCAL.equalsIgnoreCase(scope)) {
                // export to extra protocol is used in remote export
                String extProtocol = url.getParameter("ext.protocol", "");
                List<String> protocols = new ArrayList<>();

                if (StringUtils.isNotBlank(extProtocol)) {
                    // export original url
                    url = URLBuilder.from(url).
                        addParameter(IS_PU_SERVER_KEY, Boolean.TRUE.toString()).
                        removeParameter("ext.protocol").
                        build();
                }
                // 远程发布
                url = exportRemote(url, registryURLs);
                if (!isGeneric(generic) && !getScopeModel().isInternal()) {
                    MetadataUtils.publishServiceDefinition(url, providerModel.getServiceModel(), getApplicationModel());
                }

                if (StringUtils.isNotBlank(extProtocol)) {
                    String[] extProtocols = extProtocol.split(",", -1);
                    protocols.addAll(Arrays.asList(extProtocols));
                }
                // export extra protocols
                for (String protocol : protocols) {
                    if (StringUtils.isNotBlank(protocol)) {
                        URL localUrl = URLBuilder.from(url).
                            setProtocol(protocol).
                            build();
                        localUrl = exportRemote(localUrl, registryURLs);
                        if (!isGeneric(generic) && !getScopeModel().isInternal()) {
                            MetadataUtils.publishServiceDefinition(localUrl, providerModel.getServiceModel(), getApplicationModel());
                        }
                        this.urls.add(localUrl);
                    }
                }
            }
        }
        this.urls.add(url);
    }

    private URL exportRemote(URL url, List<URL> registryURLs) {
        // 注册中心地址集合为空不处理
        if (CollectionUtils.isNotEmpty(registryURLs)) {
            // 遍历每一个注册中心地址
            for (URL registryURL : registryURLs) {
                // 将服务的接口相关信息存储到内存中
                if (SERVICE_REGISTRY_PROTOCOL.equals(registryURL.getProtocol())) {
                    url = url.addParameterIfAbsent(SERVICE_NAME_MAPPING_KEY, "true");
                }

                //if protocol is only injvm ,not register
                // 包含injvm 不处理
                if (LOCAL_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
                    continue;
                }

                // 获取动态属性 dynamic true 创建临时节点 false 创建持久节点 需要人工进行干预
                url = url.addParameterIfAbsent(DYNAMIC_KEY, registryURL.getParameter(DYNAMIC_KEY));
                URL monitorUrl = ConfigValidationUtils.loadMonitor(this, registryURL);
                // 监控地址添加
                if (monitorUrl != null) {
                    url = url.putAttribute(MONITOR_KEY, monitorUrl);
                }

                // For providers, this is used to enable custom proxy to generate invoker
                // 支持自定义生成代理对象
                String proxy = url.getParameter(PROXY_KEY);
                if (StringUtils.isNotEmpty(proxy)) {
                    registryURL = registryURL.addParameter(PROXY_KEY, proxy);
                }

                if (logger.isInfoEnabled()) {
                    if (url.getParameter(REGISTER_KEY, true)) {
                        logger.info("Register dubbo service " + interfaceClass.getName() + " url " + url + " to registry " + registryURL.getAddress());
                    } else {
                        logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
                    }
                }
                // 执行导出 服务发布 ⭐️
                doExportUrl(registryURL.putAttribute(EXPORT_KEY, url), true);
            }

        } else {

            if (logger.isInfoEnabled()) {
                logger.info("Export dubbo service " + interfaceClass.getName() + " to url " + url);
            }

            doExportUrl(url, true);
        }


        return url;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void doExportUrl(URL url, boolean withMetaData) {
        //1.先执行proxyFactory接口的自适应拓展点代理类 proxyFactory$Adaptive 的getInvoker()
        //2.执行包装类 StubProxyfactoryWarapper getInvoker()
        //3.执行javaSsistProxyFactoryWarapper getInvoker()
        // 🤔 不理解
        Invoker<?> invoker = proxyFactory.getInvoker(ref, (Class) interfaceClass, url);
        if (withMetaData) {
            invoker = new DelegateProviderMetaDataInvoker(invoker, this);
        }
        // SPI导出
        Exporter<?> exporter = protocolSPI.export(invoker);
        exporters.add(exporter);
    }


    /**
     * always export injvm
     */
    private void exportLocal(URL url) {
        // dubbo://192.168.1.28:20880/org.apache.dubbo.springboot.demo.DemoService?anyhost=true&application=dubbo-springboot-demo-provider&background=false&bind.ip=192.168.1.28&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&executor-management-mode=isolation&file-cache=true&generic=false&interface=org.apache.dubbo.springboot.demo.DemoService&ipv6=2409:8a00:60c1:9140:f866:8212:f7b:9a30&methods=registerUser,sayHello,sayHelloAsync&pid=45318&prefer.serialization=fastjson2,hessian2&qos.enable=true&release=3.2.0&side=provider&timestamp=1753492552895
        URL local = URLBuilder.from(url)
            .setProtocol(LOCAL_PROTOCOL)
            .setHost(LOCALHOST_VALUE)
            .setPort(0)
            .build();
        // injvm://127.0.0.1/org.apache.dubbo.springboot.demo.DemoService?anyhost=true&application=dubbo-springboot-demo-provider&background=false&bind.ip=192.168.1.28&bind.port=20880&deprecated=false&dubbo=2.0.2&dynamic=true&executor-management-mode=isolation&file-cache=true&generic=false&interface=org.apache.dubbo.springboot.demo.DemoService&ipv6=2409:8a00:60c1:9140:f866:8212:f7b:9a30&methods=registerUser,sayHello,sayHelloAsync&pid=45318&prefer.serialization=fastjson2,hessian2&qos.enable=true&release=3.2.0&side=provider&timestamp=1753492552895
        // 可以看到进行IP端口的修改 以及协议修改成 inJVM
        local = local.setScopeModel(getScopeModel())
            .setServiceModel(providerModel);
        local = local.addParameter(EXPORTER_LISTENER_KEY, LOCAL_PROTOCOL);
        doExportUrl(local, false);
        logger.info("Export dubbo service " + interfaceClass.getName() + " to local registry url : " + local);
    }

    /**
     * Determine if it is injvm
     *
     * @return
     */
    private boolean isOnlyInJvm() {
        return getProtocols().size() == 1
            && LOCAL_PROTOCOL.equalsIgnoreCase(getProtocols().get(0).getName());
    }

    private void postProcessConfig() {
        List<ConfigPostProcessor> configPostProcessors = this.getExtensionLoader(ConfigPostProcessor.class)
            .getActivateExtension(URL.valueOf("configPostProcessor://", getScopeModel()), (String[]) null);
        configPostProcessors.forEach(component -> component.postProcessServiceConfig(this));
    }

    public void addServiceListener(ServiceListener listener) {
        this.serviceListeners.add(listener);
    }

    protected void onExported() {
        for (ServiceListener serviceListener : this.serviceListeners) {
            serviceListener.exported(this);
        }
    }

    protected void onUnexpoted() {
        for (ServiceListener serviceListener : this.serviceListeners) {
            serviceListener.unexported(this);
        }
    }

    /**
     * Register & bind IP address for service provider, can be configured separately.
     * Configuration priority: environment variables -> java system properties -> host property in config file ->
     * /etc/hosts -> default network address -> first available network address
     *
     * @param protocolConfig
     * @param map
     * @return
     */
    private static String findConfiguredHosts(ProtocolConfig protocolConfig,
                                              ProviderConfig provider,
                                              Map<String, String> map) {
        boolean anyhost = false;

        String hostToBind = getValueFromConfig(protocolConfig, DUBBO_IP_TO_BIND);
        if (StringUtils.isNotEmpty(hostToBind) && isInvalidLocalHost(hostToBind)) {
            throw new IllegalArgumentException("Specified invalid bind ip from property:" + DUBBO_IP_TO_BIND + ", value:" + hostToBind);
        }

        // if bind ip is not found in environment, keep looking up
        if (StringUtils.isEmpty(hostToBind)) {
            hostToBind = protocolConfig.getHost();
            if (provider != null && StringUtils.isEmpty(hostToBind)) {
                hostToBind = provider.getHost();
            }
            if (isInvalidLocalHost(hostToBind)) {
                anyhost = true;
                if (logger.isDebugEnabled()) {
                    logger.debug("No valid ip found from environment, try to get local host.");
                }
                hostToBind = getLocalHost();
            }
        }

        map.put(BIND_IP_KEY, hostToBind);

        // bind ip is not used for registry ip by default
        String hostToRegistry = getValueFromConfig(protocolConfig, DUBBO_IP_TO_REGISTRY);
        if (StringUtils.isNotEmpty(hostToRegistry) && isInvalidLocalHost(hostToRegistry)) {
            throw new IllegalArgumentException("Specified invalid registry ip from property:" + DUBBO_IP_TO_REGISTRY + ", value:" + hostToRegistry);
        } else if (StringUtils.isEmpty(hostToRegistry)) {
            // bind ip is used as registry ip by default
            hostToRegistry = hostToBind;
        }

        map.put(ANYHOST_KEY, String.valueOf(anyhost));

        return hostToRegistry;
    }


    /**
     * Register port and bind port for the provider, can be configured separately
     * Configuration priority: environment variable -> java system properties -> port property in protocol config file
     * -> protocol default port
     *
     * @param protocolConfig
     * @param name
     * @return
     */
    private static synchronized Integer findConfiguredPort(ProtocolConfig protocolConfig,
                                                           ProviderConfig provider,
                                                           ExtensionLoader<Protocol> extensionLoader,
                                                           String name, Map<String, String> map) {
        Integer portToBind;

        // parse bind port from environment
        String port = getValueFromConfig(protocolConfig, DUBBO_PORT_TO_BIND);
        portToBind = parsePort(port);

        // if there's no bind port found from environment, keep looking up.
        if (portToBind == null) {
            portToBind = protocolConfig.getPort();
            if (provider != null && (portToBind == null || portToBind == 0)) {
                portToBind = provider.getPort();
            }
            final int defaultPort = extensionLoader.getExtension(name).getDefaultPort();
            if (portToBind == null || portToBind == 0) {
                portToBind = defaultPort;
            }
            if (portToBind <= 0) {
                portToBind = getRandomPort(name);
                if (portToBind == null || portToBind < 0) {
                    portToBind = getAvailablePort(defaultPort);
                    putRandomPort(name, portToBind);
                }
            }
        }

        // save bind port, used as url's key later
        map.put(BIND_PORT_KEY, String.valueOf(portToBind));

        // bind port is not used as registry port by default
        String portToRegistryStr = getValueFromConfig(protocolConfig, DUBBO_PORT_TO_REGISTRY);
        Integer portToRegistry = parsePort(portToRegistryStr);
        if (portToRegistry == null) {
            portToRegistry = portToBind;
        }

        return portToRegistry;
    }

    private static Integer parsePort(String configPort) {
        Integer port = null;
        if (StringUtils.isNotEmpty(configPort)) {
            try {
                int intPort = Integer.parseInt(configPort);
                if (isInvalidPort(intPort)) {
                    throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
                }
                port = intPort;
            } catch (Exception e) {
                throw new IllegalArgumentException("Specified invalid port from env value:" + configPort);
            }
        }
        return port;
    }

    private static String getValueFromConfig(ProtocolConfig protocolConfig, String key) {
        String protocolPrefix = protocolConfig.getName().toUpperCase() + "_";
        String value = ConfigUtils.getSystemProperty(protocolPrefix + key);
        if (StringUtils.isEmpty(value)) {
            value = ConfigUtils.getSystemProperty(key);
        }
        return value;
    }

    private static Integer getRandomPort(String protocol) {
        protocol = protocol.toLowerCase();
        return RANDOM_PORT_MAP.getOrDefault(protocol, Integer.MIN_VALUE);
    }

    private static void putRandomPort(String protocol, Integer port) {
        protocol = protocol.toLowerCase();
        if (!RANDOM_PORT_MAP.containsKey(protocol)) {
            RANDOM_PORT_MAP.put(protocol, port);
            logger.warn(CONFIG_USE_RANDOM_PORT, "", "", "Use random available port(" + port + ") for protocol " + protocol);
        }
    }

    @Transient
    public Runnable getDestroyRunner() {
        return this::unexport;
    }
}
