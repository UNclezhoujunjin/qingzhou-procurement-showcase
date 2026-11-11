package com.qingzhou.procurement;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
class BackupService {
    private final JdbcTemplate db;private final Path folder;
    private final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BackupService.class);
    BackupService(JdbcTemplate db,@Value("${app.backup-dir:./backups}") String folder){this.db=db;this.folder=Path.of(folder).toAbsolutePath().normalize();}
    boolean supported() {
        return Boolean.TRUE.equals(db.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)c->{String url=c.getMetaData().getURL();return url.startsWith("jdbc:h2:")&&!url.contains(":mem:");}));
    }
    synchronized Map<String,Object> create() {
        if(!supported())throw new BusinessError(409,"内置备份仅用于本机文件数据库；PostgreSQL 请使用数据库备份工具");
        try {
            Files.createDirectories(folder);
            String stamp=LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path target=folder.resolve("qingzhou-"+stamp+"-"+UUID.randomUUID().toString().substring(0,8)+".zip");
            db.execute("BACKUP TO '"+target.toString().replace("'","''")+"'");
            String sha=hash(target);Files.writeString(Path.of(target+".sha256"),sha+"\n",StandardOpenOption.CREATE_NEW);
            return metadata(target);
        } catch(IOException e){throw new BusinessError(500,"备份写入失败，请检查备份目录的权限和磁盘空间");}
    }
    List<Map<String,Object>> list() {
        try {
            Files.createDirectories(folder);
            try(var files=Files.list(folder)) {return files.filter(p->valid(p.getFileName().toString())).sorted(Comparator.reverseOrder()).map(this::metadata).toList();}
        }catch(IOException e){throw new BusinessError(500,"无法读取备份目录");}
    }
    Path file(String name) {
        if(!valid(name))throw new BusinessError(400,"备份文件名不正确");
        Path path=folder.resolve(name).normalize();if(!path.startsWith(folder)||!Files.isRegularFile(path))throw new BusinessError(404,"备份文件不存在");return path;
    }
    Map<String,Object> verify(String name) {
        Path path=file(name);var result=metadata(path);
        try {
            String expected=Files.readString(Path.of(path+".sha256")).strip();
            result.put("valid",expected.equals(hash(path)));return result;
        }catch(IOException e){throw new BusinessError(409,"缺少校验文件或备份无法读取");}
    }
    static String hash(Path path) throws IOException {
        try(InputStream in=Files.newInputStream(path)) {
            var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int read;
            while((read=in.read(buffer))!=-1)digest.update(buffer,0,read);
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private boolean valid(String name){return name.matches("qingzhou-[0-9]{8}-[0-9]{6}-[a-f0-9]{8}\\.zip");}
    private Map<String,Object> metadata(Path p) {
        try {
            var result=new LinkedHashMap<String,Object>();result.put("name",p.getFileName().toString());result.put("size",Files.size(p));
            result.put("createdAt",Files.getLastModifiedTime(p).toInstant().toString());
            result.put("sha256",Files.exists(Path.of(p+".sha256"))?Files.readString(Path.of(p+".sha256")).strip():"");return result;
        }catch(IOException e){throw new BusinessError(500,"无法读取备份信息");}
    }
    @EventListener(ApplicationReadyEvent.class)
    public void onReady(){automatic();}
    @Scheduled(cron="${app.backup-cron:0 0 3 * * *}",zone="Asia/Shanghai")
    public void automatic() {
        if(!supported())return;
        try {
            var backups=list();
            if(backups.isEmpty()||Instant.parse(backups.get(0).get("createdAt").toString()).isBefore(Instant.now().minus(Duration.ofHours(23)))) {
                var result=create();log.info("Local database backup created: {}",result.get("name"));
            }
        }catch(Exception e){log.error("Automatic database backup failed",e);}
    }
}
