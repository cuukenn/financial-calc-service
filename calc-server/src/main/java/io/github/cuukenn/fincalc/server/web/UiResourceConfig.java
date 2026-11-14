package io.github.cuukenn.fincalc.server.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * UI 静态资源托管（input-field-audit 后的一体化部署，2026-11-14）：前端 dist 打进
 * jar 的 {@code classpath:/static}，由本配置按**显式路径**托管 —— 工作台入口 {@code /}
 * forward 到 {@code index.html}，{@code /assets/**} 与 {@code favicon} 直读静态资源。
 *
 * <p>与 R77 统一 404 的共存方式：application.yml 的
 * {@code spring.web.resources.add-mappings: false} 只关掉 Boot 默认的 {@code /**}
 * 资源映射，本类注册的**具名路径**映射不受它影响 —— 于是 {@code /index.html}、
 * {@code /assets/**} 命中本配置，其余一切未匹配路径仍走 {@code NoHandlerFoundException}
 * → {@code FCS-SRV-1005}（ApiExceptionHandler），R77 契约与
 * {@code CalculationApiTest} 的 404 断言逐字不变。
 *
 * <p>前端无客户端路由（单页 Tab 应用，无 vue-router），因此不需要 SPA 回退：
 * {@code /} 即工作台，{@code /api/**} 是唯一的应用接口前缀。
 */
@Configuration
public class UiResourceConfig implements WebMvcConfigurer {

    /** 缓存一小时：内容带内容哈希文件名（vite 产物），构建更替即换名，无需长缓存协商。 */
    private static final String CACHE_SECONDS = "3600";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/static/");
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCachePeriod(Integer.parseInt(CACHE_SECONDS));
        // 无 favicon 映射：dist 没有该文件，浏览器自动请求 /favicon.ico 落统一 404
        // （FCS-SRV-1005），不为它编一个假映射。
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 工作台入口：/ → index.html（forward 经 DispatcherServlet 再进上面的资源映射）
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
