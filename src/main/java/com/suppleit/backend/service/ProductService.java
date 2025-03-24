package com.suppleit.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suppleit.backend.dto.ProductDto;
import com.suppleit.backend.mapper.ProductMapper;
import com.suppleit.backend.model.Product;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductMapper productMapper;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${api.health-functional-food.url}")
    private String apiUrl;

    @Value("${api.health-functional-food.key}")
    private String apiKey;

    // 초기 데이터 확인 및 설정
    @PostConstruct
    public void initializeData() {
        // 데이터베이스가 비어있는지 확인
        long productCount = productMapper.getProductCount();
        if (productCount == 0) {
            log.info("제품 테이블이 비어있습니다. 기본 데이터 초기화 중...");
            
            try {
                // 샘플 제품 추가
                List<ProductDto> sampleProducts = getSampleProducts();
                for (ProductDto product : sampleProducts) {
                    Product entity = convertToEntity(product);
                    productMapper.insertProduct(entity);
                }
                log.info("기본 데이터 초기화 완료: {}건", sampleProducts.size());
            } catch (Exception e) {
                log.error("기본 데이터 초기화 중 오류: {}", e.getMessage(), e);
            }
        } else {
            log.info("제품 테이블에 {}건의 데이터가 존재합니다.", productCount);
        }
    }

    // 외부 API와 DB를 함께 사용하는 통합 검색
    public List<ProductDto> searchProducts(String keyword) {
        log.info("제품 검색 시작: {}", keyword);
        
        try {
            // 먼저 데이터베이스 검색
            List<ProductDto> dbResults = searchProductsFromDb(keyword);
            
            // 데이터베이스 결과가 충분하면 반환
            if (dbResults.size() >= 5) {
                log.info("DB에서 충분한 검색 결과 발견: {}건", dbResults.size());
                return dbResults;
            }
            
            // DB 결과가 불충분하면 외부 API 검색 시도
            log.info("DB 검색 결과 불충분 ({}건), 외부 API 검색 시도", dbResults.size());
            try {
                List<ProductDto> apiResults = searchProductsFromApi(keyword);
                
                // 결과 병합 (ID 기반 중복 제거)
                Map<Long, ProductDto> combinedResults = new HashMap<>();
                
                // DB 결과 먼저 추가
                for (ProductDto product : dbResults) {
                    combinedResults.put(product.getPrdId(), product);
                }
                
                // API 결과 추가 (중복 방지)
                for (ProductDto product : apiResults) {
                    if (!combinedResults.containsKey(product.getPrdId())) {
                        combinedResults.put(product.getPrdId(), product);
                        
                        // DB에 새 제품 저장
                        try {
                            saveProductToDb(product);
                        } catch (Exception e) {
                            log.warn("제품 저장 중 오류: {}", e.getMessage());
                        }
                    }
                }
                
                return new ArrayList<>(combinedResults.values());
                
            } catch (Exception apiError) {
                log.warn("외부 API 검색 중 오류, DB 결과만 반환: {}", apiError.getMessage());
                return dbResults;
            }
        } catch (Exception e) {
            log.error("제품 검색 중 오류: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    // DB에서 제품 검색
    private List<ProductDto> searchProductsFromDb(String keyword) {
        log.info("DB에서 제품 검색: {}", keyword);
        try {
            List<Product> products = productMapper.searchProducts(keyword);
            return products.stream()
                    .map(this::convertToDto)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("DB 검색 중 오류: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    // API에서 제품 검색
    private List<ProductDto> searchProductsFromApi(String keyword) {
        log.info("외부 API에서 제품 검색: {}", keyword);
        List<ProductDto> results = new ArrayList<>();
        
        try {
            // 한글 인코딩
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);

            log.debug("API 키: {}", apiKey);
            log.debug("인코딩된 키워드: {}", encodedKeyword);
            
            // API URL 구성
            URI uri = UriComponentsBuilder.fromUriString(apiUrl)
                .queryParam("serviceKey", apiKey)  // 인코딩된 키를 그대로 사용
                .queryParam("Prduct", encodedKeyword)
                .queryParam("pageNo", 1)
                .queryParam("numOfRows", 10)
                .queryParam("type", "json")
                .build(false)
                .toUri();

            log.debug("실제 요청 URL: {}", uri);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            HttpEntity<?> entity = new HttpEntity<>(headers);

            log.debug("API 요청 URL: {}", uri);
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                
                // 로그에 전체 응답 기록
                log.debug("API 응답: {}", root.toString());
                
                // 응답 구조 확인 및 파싱
                JsonNode header = root.path("header");
                String resultCode = header.path("resultCode").asText();
                
                if (!"00".equals(resultCode)) {
                    log.error("API 오류 응답: {}, {}", resultCode, header.path("resultMsg").asText());
                    return results;
                }
                
                JsonNode body = root.path("body");
                int totalCount = body.path("totalCount").asInt();
                log.info("검색 결과 총 건수: {}", totalCount);
                
                JsonNode items = body.path("items");
                
                if (items.isArray()) {
                    for (JsonNode itemNode : items) {
                        // items 배열 안에 item 객체가 있는 경우
                        JsonNode item = itemNode.path("item");
                        if (!item.isMissingNode()) {
                            ProductDto productDto = parseProductFromJson(item);
                            if (productDto != null) {
                                results.add(productDto);
                            }
                        } else {
                            // items 배열 자체가 item인 경우
                            ProductDto productDto = parseProductFromJson(itemNode);
                            if (productDto != null) {
                                results.add(productDto);
                            }
                        }
                    }
                } else {
                    log.warn("API 응답에 items 배열이 없습니다");
                }
                
                log.info("API에서 가져온 제품 수: {}", results.size());
            } else {
                log.warn("API 응답 오류 또는 응답 없음");
            }
        } catch (Exception e) {
            log.error("API 검색 중 오류: {}", e.getMessage(), e);
        }
        
        return results;
    }

    // 특정 제품 조회
    public ProductDto getProductById(Long productId) {
        log.info("제품 ID로 조회: {}", productId);
        Product product = productMapper.getProductById(productId);
        if (product == null) {
            throw new IllegalArgumentException("해당 제품을 찾을 수 없습니다: " + productId);
        }
        return convertToDto(product);
    }

    // JSON을 Product 객체로 파싱
    private ProductDto parseProductFromJson(JsonNode item) {
        if (item == null) {
            return null;
        }
        
        try {
            // 로그에 파싱할 항목 출력
            log.debug("파싱할 항목: {}", item.toString());
            
            String productName = getTextFromNode(item, "PRDUCT"); // 제품명
            
            // 제품명이 없으면 건너뛰기
            if (productName.isEmpty()) {
                log.warn("제품명이 없는 항목 무시");
                return null;
            }
            
            String companyName = getTextFromNode(item, "ENTRPS"); // 업체명
            String reportNo = getTextFromNode(item, "STTEMNT_NO"); // 품목제조신고번호
            
            // 제품 ID 생성
            Long prdId;
            if (!reportNo.isEmpty()) {
                // 신고번호에서 숫자만 추출
                String numericPart = reportNo.replaceAll("[^0-9]", "");
                if (!numericPart.isEmpty()) {
                    try {
                        prdId = Long.parseLong(numericPart);
                    } catch (NumberFormatException e) {
                        // 숫자로 변환 실패 시 해시코드 사용
                        prdId = (long) Math.abs(productName.hashCode() + System.currentTimeMillis() % 1000000000);
                    }
                } else {
                    prdId = (long) Math.abs(productName.hashCode() + System.currentTimeMillis() % 1000000000);
                }
            } else {
                prdId = (long) Math.abs(productName.hashCode() + System.currentTimeMillis() % 1000000000);
            }
            
            ProductDto dto = new ProductDto();
            dto.setPrdId(prdId);
            dto.setProductName(productName);
            dto.setCompanyName(companyName);
            dto.setRegistrationNo(reportNo);
            
            // 추가 정보 매핑
            dto.setExpirationPeriod(getTextFromNode(item, "DISTB_PD")); // 유통기한
            dto.setMainFunction(getTextFromNode(item, "MAIN_FNCTN")); // 주요기능
            dto.setIntakeHint(getTextFromNode(item, "INTAKE_HINT1")); // 섭취시 주의사항
            dto.setPreservation(getTextFromNode(item, "PRSRV_PD")); // 보관방법
            dto.setSrvUse(getTextFromNode(item, "SRV_USE")); // 섭취방법
            dto.setBaseStandard(getTextFromNode(item, "BASE_STANDARD")); // 기준규격
            
            log.info("파싱된 제품: {}", productName);
            return dto;
        } catch (Exception e) {
            log.error("제품 데이터 파싱 중 오류: {}", e.getMessage(), e);
            return null;
        }
    }

    // 노드에서 텍스트 안전하게 가져오기
    private String getTextFromNode(JsonNode node, String fieldName) {
        if (node == null || node.path(fieldName).isMissingNode()) {
            return "";
        }
        return node.path(fieldName).asText("").trim();
    }

    // 제품 정보를 DB에 저장
    private void saveProductToDb(ProductDto productDto) {
        try {
            // ID가 0인 경우 처리
            if (productDto.getPrdId() == 0) {
                productDto.setPrdId(generateTempId(productDto.getProductName()));
            }
            
            // 이미 존재하는지 확인
            Product existingProduct = productMapper.getProductById(productDto.getPrdId());
            
            if (existingProduct == null) {
                // 새 제품 저장
                Product product = convertToEntity(productDto);
                productMapper.insertProduct(product);
                log.info("새 제품 DB에 저장: {}", productDto.getProductName());
            } else {
                // 기존 제품 갱신
                Product product = convertToEntity(productDto);
                productMapper.updateProduct(product);
                log.info("기존 제품 정보 업데이트: {}", productDto.getProductName());
            }
        } catch (Exception e) {
            log.error("제품 저장 중 오류: {}", e.getMessage(), e);
            throw e;
        }
    }

    // 임시 ID 생성
    private Long generateTempId(String key) {
        return Math.abs((key.hashCode() + System.currentTimeMillis()) % 1000000000L);
    }

    // Entity -> DTO 변환
    private ProductDto convertToDto(Product product) {
        ProductDto dto = new ProductDto();
        dto.setPrdId(product.getPrdId());
        dto.setProductName(product.getProductName());
        dto.setCompanyName(product.getCompanyName());
        dto.setRegistrationNo(product.getRegistrationNo());
        dto.setExpirationPeriod(product.getExpirationPeriod());
        dto.setSrvUse(product.getSrvUse());
        dto.setMainFunction(product.getMainFunction());
        dto.setPreservation(product.getPreservation());
        dto.setIntakeHint(product.getIntakeHint());
        dto.setBaseStandard(product.getBaseStandard());
        return dto;
    }

    // DTO -> Entity 변환
    private Product convertToEntity(ProductDto dto) {
        Product product = new Product();
        product.setPrdId(dto.getPrdId());
        product.setProductName(dto.getProductName());
        product.setCompanyName(dto.getCompanyName());
        product.setRegistrationNo(dto.getRegistrationNo());
        product.setExpirationPeriod(dto.getExpirationPeriod());
        product.setSrvUse(dto.getSrvUse());
        product.setMainFunction(dto.getMainFunction());
        product.setPreservation(dto.getPreservation());
        product.setIntakeHint(dto.getIntakeHint());
        product.setBaseStandard(dto.getBaseStandard());
        return product;
    }
    
    // 샘플 데이터 생성
    private List<ProductDto> getSampleProducts() {
        List<ProductDto> products = new ArrayList<>();
        
        // 샘플 제품 1
        ProductDto product1 = new ProductDto();
        product1.setPrdId(1001L);
        product1.setProductName("종합비타민");
        product1.setCompanyName("건강약품");
        product1.setMainFunction("면역력 강화, 피로회복");
        products.add(product1);
        
        // 샘플 제품 2
        ProductDto product2 = new ProductDto();
        product2.setPrdId(1002L);
        product2.setProductName("오메가3");
        product2.setCompanyName("자연약품");
        product2.setMainFunction("혈행개선, 혈중 중성지질 개선");
        products.add(product2);
        
        // 샘플 제품 3
        ProductDto product3 = new ProductDto();
        product3.setPrdId(1003L);
        product3.setProductName("루테인");
        product3.setCompanyName("눈건강");
        product3.setMainFunction("눈 건강, 시력보호");
        products.add(product3);
        
        // 샘플 제품 4
        ProductDto product4 = new ProductDto();
        product4.setPrdId(1004L);
        product4.setProductName("칼슘마그네슘");
        product4.setCompanyName("뼈건강");
        product4.setMainFunction("뼈 건강, 골다공증 예방");
        products.add(product4);
        
        // 샘플 제품 5
        ProductDto product5 = new ProductDto();
        product5.setPrdId(1005L);
        product5.setProductName("프로바이오틱스");
        product5.setCompanyName("장건강");
        product5.setMainFunction("장 건강, 면역력 증진");
        products.add(product5);
        
        return products;
    }
}