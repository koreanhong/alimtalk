package com.alimtalk.template;

import com.alimtalk.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 내부 예외 메시지는 응답에 담지 않고 정해진 문구만 내려준다. */
@RestControllerAdvice
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<TemplateController.ErrorBody> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getDefaultMessage())
                .orElse("요청 형식이 올바르지 않습니다.");
        return ResponseEntity.badRequest().body(TemplateController.ErrorBody.of("BAD_REQUEST", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<TemplateController.ErrorBody> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(TemplateController.ErrorBody.of("BAD_REQUEST", "요청 형식이 올바르지 않습니다."));
    }

    @ExceptionHandler(LlmException.class)
    public ResponseEntity<TemplateController.ErrorBody> llm(LlmException e) {
        log.warn("템플릿 생성 실패: kind={}", e.kind());
        if (e.kind() == LlmException.Kind.TIMEOUT) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(TemplateController.ErrorBody.of(
                    "TIMEOUT", "응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요."));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(TemplateController.ErrorBody.of(
                "LLM_ERROR", "템플릿 생성 중 오류가 발생했습니다."));
    }
}
