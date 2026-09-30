package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BusinessException;
import com.cqwlw.maintenance.config.AppProperties;
import com.cqwlw.maintenance.entity.FileRecord;
import com.cqwlw.maintenance.mapper.FileRecordMapper;
import com.cqwlw.maintenance.security.AuthContext;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** MVP 文件存证：照片/签名保存到本地磁盘；COS 直传在后续阶段替换。 */
@Service
public class FileService {
    private final AppProperties props;
    private final FileRecordMapper fileRecordMapper;
    private final AuthContext authContext;

    public FileService(AppProperties props, FileRecordMapper fileRecordMapper, AuthContext authContext) {
        this.props = props;
        this.fileRecordMapper = fileRecordMapper;
        this.authContext = authContext;
    }

    public FileRecord store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(422, "请选择要上传的文件");
        }
        try {
            Path root = Paths.get(props.getFileRoot(), LocalDate.now().toString());
            Files.createDirectories(root);
            String originalName = file.getOriginalFilename() == null ? "file" : Paths.get(file.getOriginalFilename()).getFileName().toString();
            String extension = "";
            int dot = originalName.lastIndexOf('.');
            if (dot > -1) extension = originalName.substring(dot);
            String storageName = UUID.randomUUID().toString().replace("-", "") + extension;
            Path target = root.resolve(storageName);
            file.transferTo(target);

            FileRecord record = new FileRecord();
            record.setOriginalName(originalName);
            record.setStoragePath(target.toString());
            record.setContentType(file.getContentType());
            record.setSize(file.getSize());
            record.setOwnerPhone(authContext.currentPhone());
            record.setCreatedAt(LocalDateTime.now());
            fileRecordMapper.insert(record);
            record.setUrl("/api/v1/files/" + record.getId() + "/download");
            fileRecordMapper.updateById(record);
            return record;
        } catch (IOException e) {
            throw new BusinessException(500, "文件保存失败");
        }
    }

    public FileRecord get(Long id) {
        FileRecord record = fileRecordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(1404, "文件不存在");
        }
        return record;
    }
}
