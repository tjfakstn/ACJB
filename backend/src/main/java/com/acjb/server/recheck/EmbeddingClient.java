package com.acjb.server.recheck;

import java.util.List;

/** 문장 → 벡터. 운영은 Voyage AI, 테스트는 대역으로 바꿔 끼운다 */
public interface EmbeddingClient {

    enum InputType { QUERY, DOCUMENT }

    /** 키가 설정돼 있는지. 없으면 비동기 생성은 조용히 건너뛰고, 제안 요청은 502로 실패한다 */
    boolean isConfigured();

    /** 설정에 적힌 모델 이름 — embeddings.model 컬럼 값 */
    String model();

    /** texts와 같은 순서·같은 개수의 벡터. 실패하면 RecheckFailedException */
    List<float[]> embed(List<String> texts, InputType type);
}
