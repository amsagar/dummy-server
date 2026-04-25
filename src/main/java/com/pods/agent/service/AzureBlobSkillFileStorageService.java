package com.pods.agent.service;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.core.http.rest.Response;
import com.azure.storage.file.datalake.DataLakeDirectoryClient;
import com.azure.storage.file.datalake.DataLakeFileSystemClient;
import com.azure.storage.file.datalake.DataLakeServiceClient;
import com.azure.storage.file.datalake.DataLakeServiceClientBuilder;
import com.azure.storage.file.datalake.models.DataLakeStorageException;
import com.pods.agent.config.SkillBlobProperties;

public class AzureBlobSkillFileStorageService implements SkillFileStorageService {
    private final BlobContainerClient containerClient;
    private final DataLakeFileSystemClient fileSystemClient;

    public AzureBlobSkillFileStorageService(SkillBlobProperties props) {
        BlobServiceClient serviceClient = new BlobServiceClientBuilder()
                .connectionString(props.getConnectionString())
                .buildClient();
        this.containerClient = serviceClient.getBlobContainerClient(props.getContainer());
        if (!this.containerClient.exists()) {
            this.containerClient.create();
        }

        DataLakeServiceClient dlClient = new DataLakeServiceClientBuilder()
                .connectionString(props.getConnectionString())
                .buildClient();
        this.fileSystemClient = dlClient.getFileSystemClient(props.getContainer());
    }

    @Override
    public void put(String blobPath, byte[] content, String mimeType) {
        BlobClient blobClient = containerClient.getBlobClient(blobPath);

        // BlobParallelUploadOptions with no RequestConditions → always overwrites
        BlobParallelUploadOptions options = new BlobParallelUploadOptions(BinaryData.fromBytes(content));
        if (mimeType != null && !mimeType.isBlank()) {
            options.setHeaders(new BlobHttpHeaders().setContentType(mimeType));
        }
        blobClient.uploadWithResponse(options, null, null);
    }

    @Override
    public byte[] get(String blobPath) {
        BlobClient blobClient = containerClient.getBlobClient(blobPath);
        if (!blobClient.exists()) return new byte[0];
        return blobClient.downloadContent().toBytes();
    }

    @Override
    public void delete(String blobPath) {
        BlobClient blobClient = containerClient.getBlobClient(blobPath);
        if (blobClient.exists()) blobClient.delete();
    }

    @Override
    public void deletePrefix(String prefix) {
        // Strip trailing slash — DataLake directory client expects the bare path
        String dirPath = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        try {
            DataLakeDirectoryClient dir = fileSystemClient.getDirectoryClient(dirPath);
            // recursive=true deletes the directory and all its contents (works on HNS accounts)
            dir.deleteWithResponse(true, null, null, com.azure.core.util.Context.NONE);
        } catch (DataLakeStorageException e) {
            if (e.getStatusCode() != 404) throw e; // ignore not-found, rethrow others
        }
    }
}
