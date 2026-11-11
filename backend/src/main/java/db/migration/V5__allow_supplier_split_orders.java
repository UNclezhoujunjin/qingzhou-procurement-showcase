package db.migration;

import java.util.ArrayList;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Existing H2 and PostgreSQL installations have different generated unique-constraint names. */
public class V5__allow_supplier_split_orders extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        var connection=context.getConnection();
        var names=new ArrayList<String>();var foreignKeys=new ArrayList<String>();
        try(var query=connection.prepareStatement("""
            SELECT tc.constraint_name,tc.constraint_type FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage k ON k.constraint_name=tc.constraint_name AND k.constraint_schema=tc.constraint_schema AND k.table_name=tc.table_name
            WHERE tc.table_name='purchase_order' AND tc.constraint_type IN ('UNIQUE','FOREIGN KEY') AND tc.constraint_schema=CURRENT_SCHEMA
            GROUP BY tc.constraint_name,tc.constraint_type HAVING COUNT(*)=1 AND MAX(CAST(k.column_name AS VARCHAR))='request_id'
            """)) {
            try(var rows=query.executeQuery()) {while(rows.next()) {
                if(rows.getString(2).equals("FOREIGN KEY"))foreignKeys.add(rows.getString(1));else names.add(rows.getString(1));
            }}
        }
        try(var statement=connection.createStatement()) {
            // H2 can retain a UNIQUE backing index while a foreign key still uses it.
            for(String name:foreignKeys)statement.execute("ALTER TABLE purchase_order DROP CONSTRAINT \""+name.replace("\"","\"\"")+"\"");
            for(String name:names) statement.execute("ALTER TABLE purchase_order DROP CONSTRAINT \""+name.replace("\"","\"\"")+"\"");
            statement.execute("ALTER TABLE purchase_order ADD CONSTRAINT uq_order_request_supplier UNIQUE(request_id,supplier_id)");
            statement.execute("CREATE INDEX idx_order_request ON purchase_order(request_id)");
            statement.execute("ALTER TABLE purchase_order ADD CONSTRAINT fk_order_request_header FOREIGN KEY(request_id) REFERENCES purchase_request(id)");
        }
    }
}
