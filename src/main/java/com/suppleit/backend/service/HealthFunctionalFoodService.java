package com.suppleit.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suppleit.backend.dto.HealthFunctionalFoodDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HealthFunctionalFoodService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${api.health-functional-food.url}")
    private String apiUrl;

    @Value("${api.health-functional-food.key}")
    private String serviceKey;

    /**
     * 건강기능식품 상세정보를 검색하는 메서드
     * @param keyword 검색어 (제품명)
     * @param pageNo 페이지 번호 (기본값 1)
     * @param numOfRows 한 페이지 결과 수 (기본값 10)
     * @return 검색된 건강기능식품 목록
     */
    public List<HealthFunctionalFoodDto> searchHealthFunctionalFood(String keyword, int pageNo, int numOfRows) {
        log.info("건강기능식품 검색 시작: 키워드={}, 페이지={}, 결과수={}", keyword, pageNo, numOfRows);
        
        try {
            // API 요청 URL 구성
            URI uri = UriComponentsBuilder.fromUriString(apiUrl + "/HtfsInfoService03")
                    .queryParam("serviceKey", serviceKey)
                    .queryParam("Prduct", keyword)  // 제품명으로 검색
                    .queryParam("pageNo", pageNo)
                    .queryParam("numOfRows", numOfRows)
                    .queryParam("type", "json")
                    .build(true)  // URI 인코딩
                    .toUri();
            
            log.debug("요청 URL: {}", uri);
            
            // API 호출
            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            
            // 응답 파싱
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                
                // 응답 구조 확인
                JsonNode header = root.path("header");
                String resultCode = header.path("resultCode").asText();
                String resultMsg = header.path("resultMsg").asText();
                
                if (!"00".equals(resultCode)) {
                    log.error("API 오류 응답: {}, {}", resultCode, resultMsg);
                    return new ArrayList<>();
                }
                
                JsonNode body = root.path("body");
                int totalCount = body.path("totalCount").asInt();
                log.info("검색 결과 총 건수: {}", totalCount);
                
                List<HealthFunctionalFoodDto> results = new ArrayList<>();
                JsonNode items = body.path("items");
                
                if (items.isArray() && items.size() > 0) {
                    for (JsonNode item : items) {
                        JsonNode itemData = item.path("item");
                        if (!itemData.isMissingNode()) {
                            HealthFunctionalFoodDto dto = parseHealthFoodItem(itemData);
                            results.add(dto);
                        }
                    }
                }
                
                log.info("검색 완료: {}건 조회됨", results.size());
                return results;
            } else {
                log.error("API 응답 오류: {}", response.getStatusCode());
                return new ArrayList<>();
            }
            
        } catch (Exception e) {
            log.error("건강기능식품 검색 중 오류 발생", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 건강기능식품 상세정보 조회 메서드
     * @param sttemntNo 품목제조신고번호
     * @return 건강기능식품 상세정보
     */
    public HealthFunctionalFoodDto getHealthFunctionalFoodDetail(String sttemntNo) {
        log.info("건강기능식품 상세정보 조회: 품목제조신고번호={}", sttemntNo);
        
        try {
            // API 요청 URL 구성
            URI uri = UriComponentsBuilder.fromUriString(apiUrl + "/HtfsInfoService03")
                    .queryParam("serviceKey", serviceKey)
                    .queryParam("STTEMNT_NO", sttemntNo)  // 품목제조신고번호로 조회
                    .queryParam("pageNo", 1)
                    .queryParam("numOfRows", 1)
                    .queryParam("type", "json")
                    .build(true)  // URI 인코딩
                    .toUri();
            
            log.debug("요청 URL: {}", uri);
            
            // API 호출
            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            
            // 응답 파싱
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                
                // 응답 구조 확인
                JsonNode header = root.path("header");
                String resultCode = header.path("resultCode").asText();
                String resultMsg = header.path("resultMsg").asText();
                
                if (!"00".equals(resultCode)) {
                    log.error("API 오류 응답: {}, {}", resultCode, resultMsg);
                    return null;
                }
                
                JsonNode body = root.path("body");
                JsonNode items = body.path("items");
                
                if (items.isArray() && items.size() > 0) {
                    JsonNode itemData = items.get(0).path("item");
                    if (!itemData.isMissingNode()) {
                        return parseHealthFoodItem(itemData);
                    }
                }
                
                log.info("해당 품목제조신고번호로 조회된 결과 없음");
                return null;
            } else {
                log.error("API 응답 오류: {}", response.getStatusCode());
                return null;
            }
            
        } catch (Exception e) {
            log.error("건강기능식품 상세정보 조회 중 오류 발생", e);
            return null;
        }
    }
    
    /**
     * JSON 응답에서 건강기능식품 정보 파싱
     */
    private HealthFunctionalFoodDto parseHealthFoodItem(JsonNode item) {
        HealthFunctionalFoodDto dto = new HealthFunctionalFoodDto();
        
        try {
            dto.setPrduct(getTextSafely(item, "PRDUCT")); // 제품명
            dto.setEntrps(getTextSafely(item, "ENTRPS")); // 업체명
            dto.setSttemntNo(getTextSafely(item, "STTEMNT_NO")); // 품목제조신고번호
            dto.setRegistDt(getTextSafely(item, "REGIST_DT")); // 등록일자
            dto.setDistbPd(getTextSafely(item, "DISTB_PD")); // 유통기한
            dto.setSungsang(getTextSafely(item, "SUNGSANG")); // 성상
            dto.setSrvUse(getTextSafely(item, "SRV_USE")); // 섭취방법
            dto.setPrsrvPd(getTextSafely(item, "PRSRV_PD")); // 보관방법
            dto.setIntakeHint(getTextSafely(item, "INTAKE_HINT1")); // 섭취 시 주의사항
            dto.setMainFnctn(getTextSafely(item, "MAIN_FNCTN")); // 주요기능
            dto.setBaseStandard(getTextSafely(item, "BASE_STANDARD")); // 기준규격
        } catch (Exception e) {
            log.error("건강기능식품 정보 파싱 중 오류: {}", e.getMessage());
        }
        
        return dto;
    }
    
    /**
     * JSON 노드에서 안전하게 텍스트 추출
     */
    private String getTextSafely(JsonNode node, String fieldName) {
        if (node == null || node.path(fieldName).isMissingNode()) {
            return "";
        }
        return node.path(fieldName).asText("").trim();
    }
}