package com.qingzhou.procurement;

import java.sql.DriverManager;
import java.util.List;

class BackupProbe {
    static void verify(String path,String username,String password)throws Exception {
        String url="jdbc:h2:file:"+path+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;IFEXISTS=TRUE;ACCESS_MODE_DATA=r";
        try(var connection=DriverManager.getConnection(url,username,password);var statement=connection.createStatement()) {
            for(String table:List.of("app_user","product","purchase_request","stock_movement"))
                try(var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)){rows.next();}
        }
    }
}
