package com.erezer.admin.file;

import com.erezer.admin.security.CurrentUser;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
class FileController {

    private final FileService fileService;

    FileController(FileService fileService) {
        this.fileService = fileService;
    }

    /** Uploads images before the order is saved; the returned ids are then sent with the order. */
    @PostMapping(path = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ORDER_EDIT')")
    List<UploadedFileResponse> uploadImages(@RequestPart("files") List<MultipartFile> files) {
        return fileService.uploadImages(files, CurrentUser.id()).stream()
                .map(f -> new UploadedFileResponse(f.getId(), fileService.url(f), f.getContentType(), f.getSizeBytes()))
                .toList();
    }

    /** Uploads an expense receipt (image or PDF) before the expense is saved. */
    @PostMapping(path = "/receipts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyAuthority('EXPENSE_CREATE', 'EXPENSE_EDIT')")
    UploadedFileResponse uploadReceipt(@RequestPart("file") MultipartFile file) {
        StoredFile f = fileService.uploadReceipt(file, CurrentUser.id());
        return new UploadedFileResponse(f.getId(), fileService.url(f), f.getContentType(), f.getSizeBytes());
    }

    record UploadedFileResponse(long id, String url, String contentType, long sizeBytes) {
    }
}
