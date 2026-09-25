package com.yuyay.attachment.ocr;

import com.yuyay.config.AwsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextRequest;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;
import software.amazon.awssdk.services.textract.model.Document;
import software.amazon.awssdk.services.textract.model.S3Object;

import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TextractTextExtractor implements TextExtractor {
    private final TextractClient textractClient;
    private final AwsProperties awsProperties;

    @Override
    public String extractText(String storageKey) {
        DetectDocumentTextResponse response = textractClient.detectDocumentText(
                DetectDocumentTextRequest.builder()
                        .document(Document.builder()
                                .s3Object(S3Object.builder()
                                        .bucket(awsProperties.s3Bucket())
                                        .name(storageKey)
                                        .build())
                                .build())
                        .build());
        return response.blocks().stream()
                .filter(block -> block.blockType() == BlockType.LINE)
                .map(Block::text)
                .collect(Collectors.joining("\n"));
    }
}
