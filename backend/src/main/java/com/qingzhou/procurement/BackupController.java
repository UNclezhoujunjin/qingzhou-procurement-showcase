package com.qingzhou.procurement;

import java.util.*;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/admin/backups")
class BackupController {
    private final Accounts accounts;private final BackupService backups;private final Writes writes;
    BackupController(Accounts accounts,BackupService backups,Writes writes){this.accounts=accounts;this.backups=backups;this.writes=writes;}
    @GetMapping Map<String,Object> list(Authentication auth){accounts.current(auth).require("ADMIN");return Map.of("supported",backups.supported(),"items",backups.list());}
    @PostMapping Map<String,Object> create(Authentication auth){var a=accounts.current(auth);a.require("ADMIN");var result=backups.create();writes.audit(a,"创建数据库备份",result.get("name").toString(),"在线一致性备份；SHA-256 校验文件已生成");return result;}
    @GetMapping("/{name}/verify") Map<String,Object> verify(Authentication auth,@PathVariable String name){accounts.current(auth).require("ADMIN");return backups.verify(name);}
    @GetMapping("/{name}") ResponseEntity<FileSystemResource> download(Authentication auth,@PathVariable String name){accounts.current(auth).require("ADMIN");Path file=backups.file(name);return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name+"\"").body(new FileSystemResource(file));}
    @GetMapping("/{name}/checksum") ResponseEntity<String> checksum(Authentication auth,@PathVariable String name){accounts.current(auth).require("ADMIN");var result=backups.verify(name);return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name+".sha256\"").body(result.get("sha256")+"\n");}
}
