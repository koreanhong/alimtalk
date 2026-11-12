package com.alimtalk.rules;

import java.util.List;

/** errors는 재생성 대상(오류 수준), warnings는 사용자에게 알리기만 하는 항목(경고 수준). */
public record ValidationResult(List<String> errors, List<String> warnings) {

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
