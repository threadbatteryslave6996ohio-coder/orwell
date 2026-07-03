package dev.clippy.combined;

import com.zaxxer.hikari.HikariDataSource;
import dev.clippy.auth.ClientIdentity;
import dev.clippy.auth.ClientIdentityRepository;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@EnableJpaRepositories(
        basePackageClasses = ClientIdentityRepository.class,
        entityManagerFactoryRef = "authEntityManagerFactory",
        transactionManagerRef = "authTransactionManager"
)
class CombinedAuthDatabaseConfiguration {
    @Bean(name = "authDataSource")
    DataSource authDataSource(Environment environment) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(environment.getRequiredProperty("clippy.auth.datasource.url"))
                .username(environment.getRequiredProperty("clippy.auth.datasource.username"))
                .password(environment.getRequiredProperty("clippy.auth.datasource.password"))
                .build();
    }

    @Bean(name = "authEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean authEntityManagerFactory(
            @Qualifier("authDataSource") DataSource dataSource,
            Environment environment
    ) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(ClientIdentity.class.getPackageName());
        factory.setPersistenceUnitName("auth");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(CombinedJpaProperties.from(
                environment, "clippy.auth.jpa.hibernate.ddl-auto"));
        return factory;
    }

    @Bean(name = "authTransactionManager")
    PlatformTransactionManager authTransactionManager(
            @Qualifier("authEntityManagerFactory") EntityManagerFactory entityManagerFactory
    ) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
