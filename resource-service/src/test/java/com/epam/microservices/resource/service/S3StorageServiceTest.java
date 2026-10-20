package com.epam.microservices.resource.service;

import com.epam.microservices.resource.config.S3Properties;
import com.epam.microservices.resource.exception.StorageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3StorageServiceTest {

    private static final S3Properties PROPS =
            new S3Properties("http://localhost:4566", "us-east-1", "resource-files", "test", "test", true);

    @Mock
    private S3Client s3;

    private S3StorageService storage() {
        return new S3StorageService(s3, PROPS);
    }

    @Test
    void uploadStoresBytesAndReturnsLocation() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        byte[] data = {1, 2, 3, 4};

        String location = storage().upload(data, "audio/mpeg");

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(req.capture(), body.capture());

        assertThat(req.getValue().bucket()).isEqualTo("resource-files");
        assertThat(req.getValue().contentType()).isEqualTo("audio/mpeg");

        String key = req.getValue().key();
        assertThat(key).endsWith(".mp3");
        // The key is a random UUID with a .mp3 suffix.
        assertThat(UUID.fromString(key.substring(0, key.length() - ".mp3".length()))).isNotNull();

        assertThat(body.getValue().optionalContentLength()).hasValue((long) data.length);
        assertThat(location).isEqualTo("s3://resource-files/" + key);
    }

    @Test
    void downloadReadsBytesFromParsedLocation() {
        byte[] data = {9, 8, 7};
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), data));

        byte[] result = storage().download("s3://resource-files/abc.mp3");

        ArgumentCaptor<GetObjectRequest> req = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(req.capture());
        assertThat(req.getValue().bucket()).isEqualTo("resource-files");
        assertThat(req.getValue().key()).isEqualTo("abc.mp3");
        assertThat(result).isEqualTo(data);
    }

    @Test
    void deleteRemovesObjectAtParsedLocation() {
        storage().delete("s3://resource-files/abc.mp3");

        ArgumentCaptor<DeleteObjectRequest> req = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(req.capture());
        assertThat(req.getValue().bucket()).isEqualTo("resource-files");
        assertThat(req.getValue().key()).isEqualTo("abc.mp3");
    }

    @Test
    void uploadWrapsSdkFailure() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("boom").build());

        assertThatThrownBy(() -> storage().upload(new byte[]{1}, "audio/mpeg"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void downloadWrapsSdkFailure() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("boom").build());

        assertThatThrownBy(() -> storage().download("s3://resource-files/abc.mp3"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void deleteWrapsSdkFailure() {
        when(s3.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().message("boom").build());

        assertThatThrownBy(() -> storage().delete("s3://resource-files/abc.mp3"))
                .isInstanceOf(StorageException.class);
    }
}
