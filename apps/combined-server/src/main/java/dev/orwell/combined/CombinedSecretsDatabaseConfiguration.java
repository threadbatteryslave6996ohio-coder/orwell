package dev.orwell.combined;

import com.zaxxer.hikari.HikariDataSource;
import dev.orwell.secrets.model.AdminIdentity;
import dev.orwell.secrets.repository.AdminIdentityRepository;
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
        basePackageClasses = AdminIdentityRepository.class,
        entityManagerFactoryRef = "secretsEntityManagerFactory",
        transactionManagerRef = "secretsTransactionManager"
)
class CombinedSecretsDatabaseConfiguration {
    @Bean(name = "secretsDataSource")
    DataSource secretsDataSource(Environment environment) {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("org.postgresql.Driver")
                .url(environment.getRequiredProperty("secrets.datasource.url"))
                .username(environment.getRequiredProperty("secrets.datasource.username"))
                .password(environment.getRequiredProperty("secrets.datasource.password"))
                .build();
    }

    @Bean(name = "secretsEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean secretsEntityManagerFactory(
            @Qualifier("secretsDataSource") DataSource dataSource,
            Environment environment
    ) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(AdminIdentity.class.getPackageName());
        factory.setPersistenceUnitName("secrets");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(CombinedJpaProperties.from(
                environment, "secrets.jpa.hibernate.ddl-auto"));
        return factory;
    }

    @Bean(name = "secretsTransactionManager")
    PlatformTransactionManager secretsTransactionManager(
            @Qualifier("secretsEntityManagerFactory") EntityManagerFactory entityManagerFactory
    ) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
