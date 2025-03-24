// src/main/java/com/suppleit/backend/mapper/ProductMapper.java
package com.suppleit.backend.mapper;

import com.suppleit.backend.model.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ProductMapper {
    // 제품 ID로 조회
    Product getProductById(@Param("prdId") Long prdId);
    
    // 제품명으로 검색
    List<Product> searchProducts(@Param("keyword") String keyword);
    
    // 제품 추가
    void insertProduct(Product product);
    
    // 제품 정보 업데이트
    void updateProduct(Product product);
}