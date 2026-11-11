package com.qingzhou.procurement;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class BusinessError extends RuntimeException {
    final int status;
    BusinessError(int status, String message) { super(message); this.status = status; }
}

@RestControllerAdvice
class ErrorHandler {
    @ExceptionHandler(BusinessError.class)
    ResponseEntity<?> business(BusinessError e) {
        return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getBindingResult().getFieldErrors().stream()
            .findFirst().map(x -> x.getField() + "：" + x.getDefaultMessage()).orElse("请检查输入内容")));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(409).body(Map.of("message", "记录已存在或数据状态发生变化，请刷新后重试"));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed() {
        return ResponseEntity.badRequest().body(Map.of("message", "请求格式不正确，请检查输入内容"));
    }
}
