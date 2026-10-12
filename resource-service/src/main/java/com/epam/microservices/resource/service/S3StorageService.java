package com.epam.microservices.resource.service;

import com.epam.microservices.resource.config.S3Properties;
import com.epam.microservices.resource.exception.StorageException;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.UUID;

/**
 * Stores resources in S3 (the LocalStack emulator). Objects get a random key of
 * the form {@code <uuid>.mp3}, and the returned location is the key's canonical
 * {@code s3://<bucket>/<key>} form. Storage SDK failures are wrapped in
 * {@link StorageException}.
 */
@Service
public class S3StorageService implements StorageService {

    private final S3Client s3;
    private final S3Properties props;

    public S3StorageService(S3Client s3, S3Properties props) {
        this.s3 = s3;
        this.props = props;
    }

    @Override
    public String upload(byte[] data, String contentType) {
        String key = UUID.randomUUID() + ".mp3";
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(props.bucket())
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(data));
        } catch (SdkException e) {
            throw new StorageException("Failed to store object " + key, e);
        }
        return new S3Location(props.bucket(), key).uri();
    }

    @Override
    public byte[] download(String location) {
        S3Location loc = S3Location.parse(location);
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(loc.bucket())
                            .key(loc.key())
                            .build())
                    .asByteArray();
        } catch (SdkException e) {
            throw new StorageException("Failed to read object " + location, e);
        }
    }

    @Override
    public void delete(String location) {
        S3Location loc = S3Location.parse(location);
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(loc.bucket())
                    .key(loc.key())
                    .build());
        } catch (SdkException e) {
            throw new StorageException("Failed to delete object " + location, e);
        }
    }
}
