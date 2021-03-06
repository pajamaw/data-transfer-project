/*
 * Copyright 2018 The Data Transfer Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.datatransferproject.datatransfer.google.photos;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.json.JsonFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.datatransfer.google.common.GoogleCredentialFactory;
import org.datatransferproject.datatransfer.google.mediaModels.BatchMediaItemResponse;
import org.datatransferproject.datatransfer.google.mediaModels.GoogleAlbum;
import org.datatransferproject.datatransfer.google.mediaModels.GoogleMediaItem;
import org.datatransferproject.datatransfer.google.mediaModels.NewMediaItem;
import org.datatransferproject.datatransfer.google.mediaModels.NewMediaItemResult;
import org.datatransferproject.datatransfer.google.mediaModels.NewMediaItemUpload;
import org.datatransferproject.spi.cloud.storage.TemporaryPerJobDataStore;
import org.datatransferproject.spi.cloud.storage.TemporaryPerJobDataStore.InputStreamWrapper;
import org.datatransferproject.spi.transfer.idempotentexecutor.IdempotentImportExecutor;
import org.datatransferproject.spi.transfer.provider.ImportResult;
import org.datatransferproject.spi.transfer.provider.Importer;
import org.datatransferproject.spi.transfer.types.CopyExceptionWithFailureReason;
import org.datatransferproject.spi.transfer.types.DestinationMemoryFullException;
import org.datatransferproject.spi.transfer.types.InvalidTokenException;
import org.datatransferproject.spi.transfer.types.PermissionDeniedException;
import org.datatransferproject.transfer.ImageStreamProvider;
import org.datatransferproject.types.common.models.photos.PhotoAlbum;
import org.datatransferproject.types.common.models.photos.PhotoModel;
import org.datatransferproject.types.common.models.photos.PhotosContainerResource;
import org.datatransferproject.types.transfer.auth.TokensAndUrlAuthData;

public class GooglePhotosImporter
    implements Importer<TokensAndUrlAuthData, PhotosContainerResource> {

  // TODO: internationalize copy prefix
  private static final String COPY_PREFIX = "Copy of ";

  private final GoogleCredentialFactory credentialFactory;
  private final TemporaryPerJobDataStore jobStore;
  private final JsonFactory jsonFactory;
  private final ImageStreamProvider imageStreamProvider;
  private final Monitor monitor;
  private final double writesPerSecond;
  private volatile Map<UUID, GooglePhotosInterface> photosInterfacesMap;
  private volatile GooglePhotosInterface photosInterface;

  public GooglePhotosImporter(
      GoogleCredentialFactory credentialFactory,
      TemporaryPerJobDataStore jobStore,
      JsonFactory jsonFactory,
      Monitor monitor,
      double writesPerSecond) {
    this(
        credentialFactory,
        jobStore,
        jsonFactory,
        new HashMap<>(),
        null,
        new ImageStreamProvider(),
        monitor,
        writesPerSecond);
  }

  @VisibleForTesting
  GooglePhotosImporter(
      GoogleCredentialFactory credentialFactory,
      TemporaryPerJobDataStore jobStore,
      JsonFactory jsonFactory,
      Map<UUID, GooglePhotosInterface> photosInterfacesMap,
      GooglePhotosInterface photosInterface,
      ImageStreamProvider imageStreamProvider,
      Monitor monitor,
      double writesPerSecond) {
    this.credentialFactory = credentialFactory;
    this.jobStore = jobStore;
    this.jsonFactory = jsonFactory;
    this.photosInterfacesMap = photosInterfacesMap;
    this.photosInterface = photosInterface;
    this.imageStreamProvider = imageStreamProvider;
    this.monitor = monitor;
    this.writesPerSecond = writesPerSecond;
  }

  @Override
  public ImportResult importItem(
      UUID jobId,
      IdempotentImportExecutor idempotentImportExecutor,
      TokensAndUrlAuthData authData,
      PhotosContainerResource data)
      throws Exception {
    if (data == null) {
      // Nothing to do
      return ImportResult.OK;
    }

    // Uploads album metadata
    if (data.getAlbums() != null && data.getAlbums().size() > 0) {
      for (PhotoAlbum album : data.getAlbums()) {
        idempotentImportExecutor.executeAndSwallowIOExceptions(
            album.getId(), album.getName(), () -> importSingleAlbum(jobId, authData, album));
      }
    }

    long bytes = 0L;
    // Uploads photos
    if (data.getPhotos() != null && data.getPhotos().size() > 0) {
      for (PhotoModel photo : data.getPhotos()) {
        final PhotoResult photoResult =
            idempotentImportExecutor.executeAndSwallowIOExceptions(
                photo.getAlbumId() + "-" + photo.getDataId(),
                photo.getTitle(),
                () -> importSinglePhoto(jobId, authData, photo, idempotentImportExecutor));
        if (photoResult != null) {
          bytes += photoResult.getBytes();
        }
      }
    }

    final ImportResult result = ImportResult.OK;
    return result.copyWithBytes(bytes);
  }

  @VisibleForTesting
  String importSingleAlbum(UUID jobId, TokensAndUrlAuthData authData, PhotoAlbum inputAlbum)
      throws IOException, InvalidTokenException, PermissionDeniedException {
    // Set up album
    GoogleAlbum googleAlbum = new GoogleAlbum();
    String title = COPY_PREFIX + inputAlbum.getName();
    // Album titles are restricted to 500 characters
    // https://developers.google.com/photos/library/guides/manage-albums#creating-new-album
    if (title.length() > 500) {
      title = title.substring(0, 497) + "...";
    }
    googleAlbum.setTitle(title);

    GoogleAlbum responseAlbum =
        getOrCreatePhotosInterface(jobId, authData).createAlbum(googleAlbum);
    return responseAlbum.getId();
  }

  @VisibleForTesting
  PhotoResult importSinglePhoto(
      UUID jobId,
      TokensAndUrlAuthData authData,
      PhotoModel inputPhoto,
      IdempotentImportExecutor idempotentImportExecutor)
      throws IOException, CopyExceptionWithFailureReason {
    /*
    TODO: resumable uploads https://developers.google.com/photos/library/guides/resumable-uploads
    Resumable uploads would allow the upload of larger media that don't fit in memory.  To do this,
    however, seems to require knowledge of the total file size.
    */
    // Upload photo
    InputStream inputStream;
    Long bytes;
    if (inputPhoto.isInTempStore()) {
      final InputStreamWrapper streamWrapper =
          jobStore.getStream(jobId, inputPhoto.getFetchableUrl());
      bytes = streamWrapper.getBytes();
      inputStream = streamWrapper.getStream();
    } else {
      HttpURLConnection conn = imageStreamProvider.getConnection(inputPhoto.getFetchableUrl());
      final long contentLengthLong = conn.getContentLengthLong();
      bytes = contentLengthLong != -1 ? contentLengthLong : 0;
      inputStream = conn.getInputStream();
    }

    String uploadToken =
        getOrCreatePhotosInterface(jobId, authData).uploadPhotoContent(inputStream);

    String description = getPhotoDescription(inputPhoto);
    NewMediaItem newMediaItem = new NewMediaItem(description, uploadToken);

    String albumId;
    if (Strings.isNullOrEmpty(inputPhoto.getAlbumId())) {
      // This is ok, since NewMediaItemUpload will ignore all null values and it's possible to
      // upload a NewMediaItem without a corresponding album id.
      albumId = null;
    } else {
      // Note this will throw if creating the album failed, which is what we want
      // because that will also mark this photo as being failed.
      albumId = idempotentImportExecutor.getCachedValue(inputPhoto.getAlbumId());
    }

    NewMediaItemUpload uploadItem =
        new NewMediaItemUpload(albumId, Collections.singletonList(newMediaItem));
    try {
      BatchMediaItemResponse photoCreationResponse =
          getOrCreatePhotosInterface(jobId, authData).createPhoto(uploadItem);
      if (photoCreationResponse == null) {
        throw new IOException("The photo was not created");
      }
      NewMediaItemResult[] mediaItemResults = photoCreationResponse.getResults();
      if (mediaItemResults == null || mediaItemResults.length != 1) {
        throw new IOException("No media item results returned");
      }
      GoogleMediaItem mediaItem = mediaItemResults[0].getMediaItem();
      if (mediaItem == null) {
        throw new IOException("No media item returned");
      }
      return new PhotoResult(mediaItem.getId(), bytes);
    } catch (IOException e) {
      if (e.getMessage() != null
          && e.getMessage().contains("The remaining storage in the user's account is not enough")) {
        throw new DestinationMemoryFullException("Google destination storage full", e);
      } else {
        throw e;
      }
    }
  }

  private String getPhotoDescription(PhotoModel inputPhoto) {
    String description;
    if (Strings.isNullOrEmpty(inputPhoto.getDescription())) {
      description = "";
    } else {
      description = COPY_PREFIX + inputPhoto.getDescription();
      // Descriptions are restricted to 1000 characters
      // https://developers.google.com/photos/library/guides/upload-media#creating-media-item
      if (description.length() > 1000) {
        description = description.substring(0, 997) + "...";
      }
    }
    return description;
  }

  private synchronized GooglePhotosInterface getOrCreatePhotosInterface(
      UUID jobId, TokensAndUrlAuthData authData) {

    if (photosInterface != null) {
      return photosInterface;
    }

    if (photosInterfacesMap.containsKey(jobId)) {
      return photosInterfacesMap.get(jobId);
    }

    GooglePhotosInterface newInterface = makePhotosInterface(authData);
    photosInterfacesMap.put(jobId, newInterface);

    return newInterface;
  }

  private synchronized GooglePhotosInterface makePhotosInterface(TokensAndUrlAuthData authData) {
    Credential credential = credentialFactory.createCredential(authData);
    return new GooglePhotosInterface(
        credentialFactory, credential, jsonFactory, monitor, writesPerSecond);
  }
}
