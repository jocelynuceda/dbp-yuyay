package com.yuyay.attachment;

import com.yuyay.attachment.entity.UploadSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UploadSourceTest {

    @Test
    void acceptsTheFourKnownValues() {
        assertEquals(UploadSource.CAMERA, UploadSource.fromParam("camera"));
        assertEquals(UploadSource.GALLERY, UploadSource.fromParam("gallery"));
        assertEquals(UploadSource.FILES, UploadSource.fromParam("files"));
        assertEquals(UploadSource.WEB, UploadSource.fromParam("web"));
        assertEquals(UploadSource.CAMERA, UploadSource.fromParam(" Camera "));
    }

    /** It is informational: anything else is stored as unknown instead of rejecting the upload. */
    @Test
    void anythingElseIsUnknown() {
        for (String value : new String[]{null, "", "   ", "scanner", "camera\nforged", "CAMERA_ROLL", "web; drop table"}) {
            assertNull(UploadSource.fromParam(value), String.valueOf(value));
        }
    }
}
