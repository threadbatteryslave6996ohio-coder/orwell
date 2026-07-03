package dev.clippy.combined;

import com.zaxxer.hikari.HikariDataSource;
import dev.clippy.server.ClipboardEntry;
import dev.clippy.server.ClipboardEntryRepository;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@EnableJpaRepositories(
        basePackageClasses = ClipboardEntryRepository.class,
        entityManagerFactoryRef = "clipboardEntityManagerFactory",
        transactionManagerRef = "clipboardTransactionManager"
)
class CombinedClipboardDatabaseConfiguration {
    @Bean(name = "clipboardDataSource")
    @Primary
    DataSource clipboardDataSource(Environment environment) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(environment.getRequiredProperty("spring.datasource.url"))
                .username(environment.getRequiredProperty("spring.datasource.username"))
                .password(environment.getRequiredProperty("spring.datasource.password"))
                .build();
    }

    @Bean(name = "clipboardEntityManagerFactory")
    @Primary
    LocalContainerEntityManagerFactoryBean clipboardEntityManagerFactory(
            @Qualifier("clipboardDataSource") DataSource dataSource,
            Environment environment
    ) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(ClipboardEntry.class.getPackageName());
        factory.setPersistenceUnitName("clipboard");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(CombinedJpaProperties.from(
                environment, "clippy.clipboard.jpa.hibernate.ddl-auto"));
        return factory;
    }

    @Bean(name = "clipboardTransactionManager")
    @Primary
    PlatformTransactionManager clipboardTransactionManager(
            @Qualifier("clipboardEntityManagerFactory") EntityManagerFactory entityManagerFactory
    ) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
