package com.qingzhou.procurement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class Application {
    public static void main(String[] args) throws Exception {
        if(args.length==1&&args[0].equals("--password-hash-stdin")) {
            String password=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8)).readLine();
            System.out.println(hashPassword(password));
            return;
        }
        if(args.length==1&&args[0].startsWith("--verify-backup-db=")) {
            String path=args[0].substring("--verify-backup-db=".length());
            BackupProbe.verify(path,System.getenv().getOrDefault("DB_USER","sa"),System.getenv().getOrDefault("DB_PASSWORD",""));
            System.out.println("Backup database validation passed.");
            return;
        }
        SpringApplication.run(Application.class, args);
    }
    static String hashPassword(String password) {
        if(password==null||password.isBlank()||password.length()<8||password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new IllegalArgumentException("Password must contain at least 8 characters and at most 72 UTF-8 bytes.");
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password);
    }
}
