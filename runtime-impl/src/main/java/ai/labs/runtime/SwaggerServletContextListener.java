package ai.labs.runtime;

import io.swagger.config.ScannerFactory;
import io.swagger.jaxrs.config.BeanConfig;

import javax.enterprise.context.ApplicationScoped;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/**
 * @author ginccc
 */
@ApplicationScoped
public class SwaggerServletContextListener implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent event) {
        BeanConfig beanConfig = getBeanConfig();
        ServletContext servletContext = event.getServletContext();
        servletContext.setAttribute("scanner", ScannerFactory.getScanner());
        servletContext.setAttribute("swagger", beanConfig.getSwagger());
        servletContext.setAttribute("reader", beanConfig);
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
    }

    private BeanConfig getBeanConfig() {
    /*    BeanConfig beanConfig = new BeanConfig();
        beanConfig.setHost(getConfig("swagger.host"));
        beanConfig.setSchemes(getConfig("swagger.schemes").split(","));
        beanConfig.setTitle(getConfig("swagger.title"));
        beanConfig.setVersion(getConfig("swagger.version"));
        beanConfig.setContact(getConfig("swagger.contact"));
        beanConfig.setLicense(getConfig("swagger.license"));
        beanConfig.setBasePath(getConfig("swagger.base_path"));
        beanConfig.setLicenseUrl(getConfig("swagger.licenseUrl"));
        beanConfig.setDescription(getConfig("swagger.description"));
        beanConfig.setPrettyPrint(getConfig("swagger.pretty_print"));
        beanConfig.setTermsOfServiceUrl(getConfig("swagger.terms_of_service_url"));

        // Must be called last
        beanConfig.setResourcePackage(resourcePackages());
        beanConfig.setScan(true);

        Swagger swagger = beanConfig.getSwagger();

        if ("basic".equals(getConfig("webServer.securityHandlerType"))) {
            swagger.securityDefinition("eddi_auth", new BasicAuthDefinition());
        } else if ("keycloak".equals(getConfig("webServer.securityHandlerType"))) {
            OAuth2Definition oAuth2Definition = new OAuth2Definition()
                    .implicit(getConfig("swagger.oauth2.implicitAuthorizationUrl"));
            oAuth2Definition.setDescription("client_id is 'eddi-engine'");
            swagger.securityDefinition("eddi_auth", oAuth2Definition);
        }

        return beanConfig;*/
        return null;
    }
}