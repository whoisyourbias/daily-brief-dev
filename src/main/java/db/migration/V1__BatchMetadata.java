package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

public class V1__BatchMetadata extends BaseJavaMigration {

    @Override
    public void migrate(Context context) {
        var schema = new ClassPathResource("org/springframework/batch/core/schema-postgresql.sql");
        new ResourceDatabasePopulator(schema).populate(context.getConnection());
    }
}
