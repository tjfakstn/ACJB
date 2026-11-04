package com.acjb.server.service;

import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.acjb.server.common.ApiException;
import com.acjb.server.domain.StyleMismatch;
import com.acjb.server.domain.StyleProperty;
import com.acjb.server.dto.QaIssueResponse.StyleMismatchView;
import com.acjb.server.dto.StyleMismatchListResponse;
import com.acjb.server.repository.StyleMismatchRepository;

/** 스타일 차이 목록·상세. 사람 입력(manual)과 scan 결과가 같은 테이블이라 둘 다 나온다 */
@Service
@Transactional(readOnly = true)
public class StyleMismatchQueryService {

    private final StyleMismatchRepository mismatches;

    public StyleMismatchQueryService(StyleMismatchRepository mismatches) {
        this.mismatches = mismatches;
    }

    public StyleMismatchListResponse list(Long projectId, String frameId, String property) {
        StyleProperty propertyFilter = property == null ? null : parseProperty(property);
        String frameFilter = frameId == null || frameId.isBlank() ? null : frameId;
        var items = mismatches.search(projectId, frameFilter, propertyFilter).stream().map(StyleMismatchView::of).toList();
        return new StyleMismatchListResponse(items, items.size());
    }

    /** 다른 프로젝트의 기록은 같은 404로 숨긴다(N10) */
    public StyleMismatchView get(Long projectId, String mismatchId) {
        Long id;
        try {
            id = Long.valueOf(mismatchId.trim());
        } catch (NumberFormatException e) {
            id = null;
        }
        StyleMismatch m = id == null ? null : mismatches.findByIdAndProjectId(id, projectId).orElse(null);
        if (m == null) {
            throw ApiException.notFound("스타일 차이를 찾을 수 없습니다.");
        }
        return StyleMismatchView.of(m);
    }

    private static StyleProperty parseProperty(String raw) {
        for (StyleProperty p : StyleProperty.values()) {
            if (p.name().toLowerCase(Locale.ROOT).equals(raw)) {
                return p;
            }
        }
        throw ApiException.badRequest("property 값이 올바르지 않습니다: " + raw);
    }
}
