package com.yuyay.attachment;

import com.yuyay.attachment.ocr.TextractTextExtractor;
import com.yuyay.config.AwsProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextRequest;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;
import software.amazon.awssdk.services.textract.model.UnsupportedDocumentException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Textract devuelve bloques PAGE, LINE y WORD: solo las lineas deben llegar al cuidador, en orden. */
class TextractTextExtractorTest {

    @Test
    void keepsOnlyLinesInOrderAndReadsFromConfiguredBucket() {
        TextractClient client = mock(TextractClient.class);
        when(client.detectDocumentText(any(DetectDocumentTextRequest.class)))
                .thenReturn(DetectDocumentTextResponse.builder().blocks(
                        Block.builder().blockType(BlockType.PAGE).build(),
                        Block.builder().blockType(BlockType.LINE).text("Metformina 850 mg").build(),
                        Block.builder().blockType(BlockType.WORD).text("Metformina").build(),
                        Block.builder().blockType(BlockType.LINE).text("1 tableta cada 12 horas").build()
                ).build());

        TextractTextExtractor extractor = new TextractTextExtractor(client, new AwsProperties("us-east-1", "yuyay-bucket"));
        String text = extractor.extractText("care-subjects/7/receta.jpg");

        assertEquals("Metformina 850 mg\n1 tableta cada 12 horas", text);
        ArgumentCaptor<DetectDocumentTextRequest> captor = ArgumentCaptor.forClass(DetectDocumentTextRequest.class);
        verify(client).detectDocumentText(captor.capture());
        assertEquals("yuyay-bucket", captor.getValue().document().s3Object().bucket());
        assertEquals("care-subjects/7/receta.jpg", captor.getValue().document().s3Object().name());
    }

    @Test
    void returnsEmptyTextWhenNothingIsDetected() {
        TextractClient client = mock(TextractClient.class);
        when(client.detectDocumentText(any(DetectDocumentTextRequest.class)))
                .thenReturn(DetectDocumentTextResponse.builder()
                        .blocks(Block.builder().blockType(BlockType.PAGE).build()).build());

        TextractTextExtractor extractor = new TextractTextExtractor(client, new AwsProperties("us-east-1", "yuyay-bucket"));
        assertEquals("", extractor.extractText("care-subjects/7/en-blanco.jpg"));
    }

    @Test
    void propagatesTextractErrorsSoTheListenerMarksFailed() {
        TextractClient client = mock(TextractClient.class);
        when(client.detectDocumentText(any(DetectDocumentTextRequest.class)))
                .thenThrow(UnsupportedDocumentException.builder().message("Request has unsupported document format").build());

        TextractTextExtractor extractor = new TextractTextExtractor(client, new AwsProperties("us-east-1", "yuyay-bucket"));
        assertThrows(UnsupportedDocumentException.class, () -> extractor.extractText("care-subjects/7/varias-paginas.pdf"));
    }
}
