package com.smms.auth;

import com.smms.controller.WorkOrderController;
import com.smms.entity.User;
import com.smms.enums.UserRole;
import com.smms.exception.GlobalExceptionHandler;
import com.smms.exception.ResourceNotFoundException;
import com.smms.service.AttachmentService;
import com.smms.service.WorkOrderService;
import com.smms.util.CurrentUserArgumentResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of the attachment endpoints: safe download headers and the upload status codes. */
class AttachmentEndpointsTest {

    @TempDir Path tmp;
    private AttachmentService attachments;
    private MockMvc mvc;
    private final UUID woId = UUID.randomUUID();
    private final UUID attId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        attachments = mock(AttachmentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new WorkOrderController(mock(WorkOrderService.class), attachments))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
            .build();
        User actor = new User();
        actor.setId(UUID.randomUUID());
        actor.setRole(UserRole.technician);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private MvcResult download(String fileName, String contentType, byte[] bytes, String accept) throws Exception {
        Path f = tmp.resolve("blob");
        Files.write(f, bytes);
        when(attachments.download(eq(woId), eq(attId), any())).thenReturn(
            new AttachmentService.Download(f, fileName, contentType, bytes.length));
        var req = get("/api/work-orders/" + woId + "/attachments/" + attId + "/content");
        if (accept != null) req.header("Accept", accept);
        return mvc.perform(req).andExpect(status().isOk()).andReturn();
    }

    @Test
    void downloadSendsBytesWithEverySafetyHeader() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 1, 2, 3};
        MvcResult r = download("photo.png", "image/png", png, null);
        var res = r.getResponse();
        assertThat(res.getContentAsByteArray()).isEqualTo(png);
        assertThat(res.getContentType()).isEqualTo("image/png");
        assertThat(res.getHeader("Content-Length")).isEqualTo(String.valueOf(png.length));
        assertThat(res.getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(res.getHeader("Content-Security-Policy")).isEqualTo("default-src 'none'; sandbox");
        assertThat(res.getHeader("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
    }

    @Test
    void downloadWorksWhenTheClientOnlyAcceptsJson() throws Exception {
        // the frontend sends Accept: application/json by default; the stored type must still win
        MvcResult r = download("a.pdf", "application/pdf", "%PDF-1.4".getBytes(), "application/json");
        assertThat(r.getResponse().getContentType()).isEqualTo("application/pdf");
    }

    @Test
    void arabicFileNameIsEncodedAsRfc5987() throws Exception {
        String cd = download("تقرير الصيانة.pdf", "application/pdf", new byte[] {1}, null)
            .getResponse().getHeader("Content-Disposition");
        assertThat(cd).startsWith("attachment").contains("filename*=UTF-8''");
        assertThat(cd).doesNotContain("تقرير");
        assertThat(java.net.URLDecoder.decode(cd.substring(cd.indexOf("''") + 2).replace("+", "%2B"),
            java.nio.charset.StandardCharsets.UTF_8)).startsWith("تقرير الصيانة.pdf");
    }

    @Test
    void controlCharactersQuotesAndSeparatorsCannotInjectHeaders() throws Exception {
        String evil = "a\r\nSet-Cookie: x=1\r\n\r\n<script>.png";
        var res = download(evil, "image/png", new byte[] {1}, null).getResponse();
        String cd = res.getHeader("Content-Disposition");
        assertThat(cd).doesNotContain("\r").doesNotContain("\n");
        assertThat(res.getHeader("Set-Cookie")).isNull();
        assertThat(res.getHeaderNames()).doesNotContain("Set-Cookie");

        String quoted = download("x\"; filename=\"evil.html", "image/png", new byte[] {1}, null)
            .getResponse().getHeader("Content-Disposition");
        assertThat(quoted).doesNotContain("evil.html\"").startsWith("attachment");
    }

    @Test
    void notFoundIs404WithoutALeak() throws Exception {
        when(attachments.download(any(), any(), any())).thenThrow(new ResourceNotFoundException("Attachment not found"));
        mvc.perform(get("/api/work-orders/" + woId + "/attachments/" + attId + "/content"))
            .andExpect(status().isNotFound());
    }

    @Test
    void invalidIdsAre400() throws Exception {
        mvc.perform(get("/api/work-orders/not-a-uuid/attachments/" + attId + "/content")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/work-orders/" + woId + "/attachments/..%2F..%2Fetc/content")).andExpect(status().is4xxClientError());
    }

    // ── upload ──────────────────────────────────────────────────

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload() {
        return multipart("/api/work-orders/" + woId + "/attachments")
            .file(new MockMultipartFile("file", "a.png", "image/png", new byte[] {1}));
    }

    @Test
    void uploadCreatedIs201() throws Exception {
        when(attachments.upload(eq(woId), any(), any(), isNull())).thenReturn(AttachmentService.UploadOutcome.CREATED);
        mvc.perform(upload()).andExpect(status().isCreated());
    }

    @Test
    void replayIs200WithTheReplayHeader() throws Exception {
        when(attachments.upload(eq(woId), any(), any(), eq("retry-key-0001"))).thenReturn(AttachmentService.UploadOutcome.REPLAYED);
        mvc.perform(upload().header("Idempotency-Key", "retry-key-0001"))
            .andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void keyReusedWithOtherContentIs422() throws Exception {
        when(attachments.upload(eq(woId), any(), any(), any())).thenReturn(AttachmentService.UploadOutcome.KEY_REUSED);
        mvc.perform(upload().header("Idempotency-Key", "retry-key-0001")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void badKeyIs400() throws Exception {
        when(attachments.upload(eq(woId), any(), any(), eq("bad")))
            .thenThrow(new IllegalArgumentException("Idempotency-Key must be 8-64 characters"));
        mvc.perform(upload().header("Idempotency-Key", "bad")).andExpect(status().isBadRequest());
    }

    @Test
    void uploadOutOfScopeIs404() throws Exception {
        when(attachments.upload(any(), any(), any(), any())).thenThrow(new ResourceNotFoundException("WorkOrder not found"));
        mvc.perform(upload()).andExpect(status().isNotFound());
    }
}
