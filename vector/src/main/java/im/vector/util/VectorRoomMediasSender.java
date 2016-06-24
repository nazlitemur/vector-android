/* 
 * Copyright 2016 OpenMarket Ltd
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.util;

import android.app.AlertDialog;
import android.content.ClipDescription;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.support.v4.app.FragmentManager;
import android.text.Html;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.MyUser;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.db.MXMediasCache;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.util.ImageUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import im.vector.R;
import im.vector.activity.CommonActivityUtils;
import im.vector.activity.VectorMediasPickerActivity;
import im.vector.activity.VectorRoomActivity;
import im.vector.fragments.ImageSizeSelectionDialogFragment;
import im.vector.fragments.VectorMessageListFragment;

// VectorRoomMediasSender helps the vectorRoomActivity to manage medias .
public class VectorRoomMediasSender {
    private static final String LOG_TAG = "VectorRoomMedHelp";

    private static final String TAG_FRAGMENT_IMAGE_SIZE_DIALOG = "TAG_FRAGMENT_IMAGE_SIZE_DIALOG";

    private static final String PENDING_THUMBNAIL_URL = "PENDING_THUMBNAIL_URL";
    private static final String PENDING_MEDIA_URL = "PENDING_MEDIA_URL";
    private static final String PENDING_MIMETYPE = "PENDING_MIMETYPE";
    private static final String PENDING_FILENAME = "PENDING_FILENAME";
    private static final String KEY_BUNDLE_PENDING_QUALITY_IMAGE_POPUP = "KEY_BUNDLE_PENDING_QUALITY_IMAGE_POPUP";

    // max image sizes
    private static final int LARGE_IMAGE_SIZE = 2000;
    private static final int MEDIUM_IMAGE_SIZE = 1000;
    private static final int SMALL_IMAGE_SIZE = 500;

    // pending infos
    private String mPendingThumbnailUrl;
    private String mPendingMediaUrl;
    private String mPendingMimeType;
    private String mPendingFilename;
    private boolean mImageQualityPopUpInProgress;

    private AlertDialog mImageSizesListDialog;

    // the linked room activity
    private VectorRoomActivity mVectorRoomActivity;

    // the room fragment
    private VectorMessageListFragment mVectorMessageListFragment;

    // the medias cache
    private MXMediasCache mMediasCache;

    /**
     * Constructor
     * @param roomActivity the room activity.
     */
    public VectorRoomMediasSender(VectorRoomActivity roomActivity, VectorMessageListFragment vectorMessageListFragment, MXMediasCache mediasCache) {
        mVectorRoomActivity = roomActivity;
        mVectorMessageListFragment = vectorMessageListFragment;
        mMediasCache = mediasCache;
    }

    /**
     * Restore some saved info.
     * @param savedInstanceState the bundle
     */
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        if (null != savedInstanceState) {
            if (savedInstanceState.containsKey(PENDING_THUMBNAIL_URL)) {
                mPendingThumbnailUrl = savedInstanceState.getString(PENDING_THUMBNAIL_URL);
            }

            if (savedInstanceState.containsKey(PENDING_MEDIA_URL)) {
                mPendingMediaUrl = savedInstanceState.getString(PENDING_MEDIA_URL);
            }

            if (savedInstanceState.containsKey(PENDING_MIMETYPE)) {
                mPendingMimeType = savedInstanceState.getString(PENDING_MIMETYPE);
            }

            if (savedInstanceState.containsKey(PENDING_FILENAME)) {
                mPendingFilename = savedInstanceState.getString(PENDING_FILENAME);
            }

            // indicate if an image camera upload was in progress (image quality "Send as" dialog displayed).
            mImageQualityPopUpInProgress = savedInstanceState.getBoolean(KEY_BUNDLE_PENDING_QUALITY_IMAGE_POPUP, false);
        }
    }

    public void onSaveInstanceState(Bundle savedInstanceState) {

        if (null != mPendingThumbnailUrl) {
            savedInstanceState.putString(PENDING_THUMBNAIL_URL, mPendingThumbnailUrl);
        }

        if (null != mPendingMediaUrl) {
            savedInstanceState.putString(PENDING_MEDIA_URL, mPendingMediaUrl);
        }

        if (null != mPendingMimeType) {
            savedInstanceState.putString(PENDING_MIMETYPE, mPendingMimeType);
        }

        if (null != mPendingFilename) {
            savedInstanceState.putString(PENDING_FILENAME, mPendingFilename);
        }

        savedInstanceState.putBoolean(KEY_BUNDLE_PENDING_QUALITY_IMAGE_POPUP, mImageQualityPopUpInProgress);
    }

    /**
     * Resume any camera image upload that could have been in progress and
     * stopped due to activity lifecycle event.
     */
    public void resumeResizeMediaAndSend() {
        if (mImageQualityPopUpInProgress) {
            mVectorRoomActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    resizeMediaAndSend();
                }
            });
        }
    }

    /**
     * Send a list of images from their URIs
     * @param sharedDataItems the media URIs
     */
    public void sendMedias(final List<SharedDataItem> sharedDataItems) {
        mVectorRoomActivity.cancelSelectionMode();
        mVectorRoomActivity.setProgressVisibility(View.VISIBLE);

        final HandlerThread handlerThread = new HandlerThread("MediasEncodingThread");
        handlerThread.start();

        final android.os.Handler handler = new android.os.Handler(handlerThread.getLooper());

        Runnable r = new Runnable() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    public void run() {
                        final int mediaCount = sharedDataItems.size();

                        for (SharedDataItem sharedDataItem : sharedDataItems) {
                            String mimeType = sharedDataItem.getMimeType(mVectorRoomActivity);

                            if (TextUtils.equals(ClipDescription.MIMETYPE_TEXT_INTENT, mimeType)) {
                                // don't know how to manage it
                                break;
                            } else if (TextUtils.equals(ClipDescription.MIMETYPE_TEXT_PLAIN, mimeType) || TextUtils.equals(ClipDescription.MIMETYPE_TEXT_HTML, mimeType)) {
                                CharSequence sequence = sharedDataItem.getText();
                                String htmlText = sharedDataItem.getHtmlText();
                                String text;

                                if (null == sequence) {
                                    if (null != htmlText) {
                                        text = Html.fromHtml(htmlText).toString();
                                    } else {
                                        text = htmlText;
                                    }
                                } else {
                                    text = sequence.toString();
                                }

                                final String fText = text;
                                final String fHtmlText = htmlText;

                                mVectorRoomActivity.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        mVectorRoomActivity.sendMessage(fText, fHtmlText, "org.matrix.custom.html");
                                    }
                                });

                                break;
                            }

                            // check if it is an uri
                            // else we don't know what to do
                            if (null == sharedDataItem.getUri()) {
                                return;
                            }

                            final String fFilename = sharedDataItem.getFileName(mVectorRoomActivity);

                            ResourceUtils.Resource resource = ResourceUtils.openResource(mVectorRoomActivity, sharedDataItem.getUri(), sharedDataItem.getMimeType(mVectorRoomActivity));

                            if (null == resource) {
                                mVectorRoomActivity.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        handlerThread.quit();
                                        mVectorRoomActivity.setProgressVisibility(View.GONE);

                                        Toast.makeText(mVectorRoomActivity,
                                                mVectorRoomActivity.getString(R.string.room_message_file_not_found),
                                                Toast.LENGTH_LONG).show();
                                    }

                                });

                                return;
                            }

                            // save the file in the filesystem
                            String mediaUrl = mMediasCache.saveMedia(resource.contentStream, null, resource.mimeType);
                            Boolean isManaged = false;

                            if ((null != resource.mimeType) && resource.mimeType.startsWith("image/")) {
                                // manage except if there is an error
                                isManaged = true;

                                // try to retrieve the gallery thumbnail
                                // if the image comes from the gallery..
                                Bitmap thumbnailBitmap = null;
                                Bitmap defaultThumbnailBitmap = null;

                                try {
                                    ContentResolver resolver = mVectorRoomActivity.getContentResolver();

                                    List uriPath = sharedDataItem.getUri().getPathSegments();
                                    long imageId;
                                    String lastSegment = (String) uriPath.get(uriPath.size() - 1);

                                    // > Kitkat
                                    if (lastSegment.startsWith("image:")) {
                                        lastSegment = lastSegment.substring("image:".length());
                                    }

                                    imageId = Long.parseLong(lastSegment);
                                    defaultThumbnailBitmap = MediaStore.Images.Thumbnails.getThumbnail(resolver, imageId, MediaStore.Images.Thumbnails.MINI_KIND, null);
                                    thumbnailBitmap = MediaStore.Images.Thumbnails.getThumbnail(resolver, imageId, MediaStore.Images.Thumbnails.FULL_SCREEN_KIND, null);
                                } catch (Exception e) {
                                    Log.e(LOG_TAG, "MediaStore.Images.Thumbnails.getThumbnail " + e.getMessage());
                                }

                                // the medias picker stores its own thumbnail to avoid inflating large one
                                if (null == thumbnailBitmap) {
                                    try {
                                        String thumbPath = VectorMediasPickerActivity.getThumbnailPath(sharedDataItem.getUri().getPath());

                                        if (null != thumbPath) {
                                            File thumbFile = new File(thumbPath);

                                            if (thumbFile.exists()) {
                                                BitmapFactory.Options options = new BitmapFactory.Options();
                                                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                                                thumbnailBitmap = BitmapFactory.decodeFile(thumbPath, options);
                                            }
                                        }
                                    } catch (Exception e) {
                                        Log.e(LOG_TAG, "cannot restore the medias picker thumbnail " + e.getMessage());
                                    }
                                }

                                double thumbnailWidth = mVectorMessageListFragment.getMaxThumbnailWith();
                                double thumbnailHeight = mVectorMessageListFragment.getMaxThumbnailHeight();

                                // no thumbnail has been found or the mimetype is unknown
                                if ((null == thumbnailBitmap) || (thumbnailBitmap.getHeight() > thumbnailHeight) || (thumbnailBitmap.getWidth() > thumbnailWidth)) {
                                    // need to decompress the high res image
                                    BitmapFactory.Options options = new BitmapFactory.Options();
                                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                                    resource = ResourceUtils.openResource(mVectorRoomActivity, sharedDataItem.getUri(), sharedDataItem.getMimeType(mVectorRoomActivity));

                                    // get the full size bitmap
                                    Bitmap fullSizeBitmap = null;

                                    if (null == thumbnailBitmap) {
                                        fullSizeBitmap = BitmapFactory.decodeStream(resource.contentStream, null, options);
                                    }

                                    if ((fullSizeBitmap != null) || (thumbnailBitmap != null)) {
                                        double imageWidth;
                                        double imageHeight;

                                        if (null == thumbnailBitmap) {
                                            imageWidth = fullSizeBitmap.getWidth();
                                            imageHeight = fullSizeBitmap.getHeight();
                                        } else {
                                            imageWidth = thumbnailBitmap.getWidth();
                                            imageHeight = thumbnailBitmap.getHeight();
                                        }

                                        if (imageWidth > imageHeight) {
                                            thumbnailHeight = thumbnailWidth * imageHeight / imageWidth;
                                        } else {
                                            thumbnailWidth = thumbnailHeight * imageWidth / imageHeight;
                                        }

                                        try {
                                            thumbnailBitmap = Bitmap.createScaledBitmap((null == fullSizeBitmap) ? thumbnailBitmap : fullSizeBitmap, (int) thumbnailWidth, (int) thumbnailHeight, false);
                                        } catch (OutOfMemoryError ex) {
                                            Log.e(LOG_TAG, "Bitmap.createScaledBitmap " + ex.getMessage());
                                        }
                                    }

                                    // the valid mimetype is not provided
                                    if ("image/*".equals(mimeType)) {
                                        // make a jpg snapshot.
                                        mimeType = null;
                                    }

                                    // unknown mimetype
                                    if ((null == mimeType) || (mimeType.startsWith("image/"))) {
                                        try {
                                            // try again
                                            if (null == fullSizeBitmap) {
                                                System.gc();
                                                fullSizeBitmap = BitmapFactory.decodeStream(resource.contentStream, null, options);
                                            }

                                            if (null != fullSizeBitmap) {
                                                Uri uri = Uri.parse(mediaUrl);

                                                if (null == mimeType) {
                                                    // the images are save in jpeg format
                                                    mimeType = "image/jpeg";
                                                }

                                                resource.contentStream.close();
                                                resource = ResourceUtils.openResource(mVectorRoomActivity, sharedDataItem.getUri(), sharedDataItem.getMimeType(mVectorRoomActivity));

                                                try {
                                                    mMediasCache.saveMedia(resource.contentStream, uri.getPath(), mimeType);
                                                } catch (OutOfMemoryError ex) {
                                                    Log.e(LOG_TAG, "mMediasCache.saveMedia" + ex.getMessage());
                                                }

                                            } else {
                                                isManaged = false;
                                            }

                                            resource.contentStream.close();

                                        } catch (Exception e) {
                                            isManaged = false;
                                            Log.e(LOG_TAG, "sendMedias " + e.getMessage());
                                        }
                                    }

                                    // reduce the memory consumption
                                    if (null != fullSizeBitmap) {
                                        fullSizeBitmap.recycle();
                                        System.gc();
                                    }
                                }

                                if (null == thumbnailBitmap) {
                                    thumbnailBitmap = defaultThumbnailBitmap;
                                }

                                String thumbnailURL = mMediasCache.saveBitmap(thumbnailBitmap, null);

                                if (null != thumbnailBitmap) {
                                    thumbnailBitmap.recycle();
                                }

                                //
                                if (("image/jpg".equals(mimeType) || "image/jpeg".equals(mimeType)) && (null != mediaUrl)) {

                                    Uri imageUri = Uri.parse(mediaUrl);
                                    // get the exif rotation angle
                                    final int rotationAngle = ImageUtils.getRotationAngleForBitmap(mVectorRoomActivity, imageUri);

                                    if (0 != rotationAngle) {
                                        // always apply the rotation to the image
                                        ImageUtils.rotateImage(mVectorRoomActivity, thumbnailURL, rotationAngle, mMediasCache);

                                        // the high res media orientation should be not be done on uploading
                                        //ImageUtils.rotateImage(RoomActivity.this, mediaUrl, rotationAngle, mMediasCache))
                                    }
                                }

                                // is the image content valid ?
                                if (isManaged && (null != thumbnailURL)) {

                                    final String fThumbnailURL = thumbnailURL;
                                    final String fMediaUrl = mediaUrl;
                                    final String fMimeType = mimeType;

                                    mVectorRoomActivity.runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            // if there is only one image
                                            if (mediaCount == 1) {

                                                // display an image preview before sending it
                                                mPendingThumbnailUrl = fThumbnailURL;
                                                mPendingMediaUrl = fMediaUrl;
                                                mPendingMimeType = fMimeType;
                                                mPendingFilename = fFilename;
                                                mVectorMessageListFragment.scrollToBottom();

                                                mVectorRoomActivity.manageSendMoreButtons();

                                                mVectorRoomActivity.runOnUiThread(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        resizeMediaAndSend();
                                                    }
                                                });
                                            } else {

                                                // apply a rotation
                                                if (null != fThumbnailURL) {
                                                    // check if the media could be resized
                                                    if ("image/jpeg".equals(fMimeType)) {

                                                        System.gc();

                                                        try {
                                                            Uri uri = Uri.parse(fMediaUrl);

                                                            final int rotationAngle = ImageUtils.getRotationAngleForBitmap(mVectorRoomActivity, uri);

                                                            // try to apply exif rotation
                                                            if (0 != rotationAngle) {
                                                                // rotate the image content
                                                                ImageUtils.rotateImage(mVectorRoomActivity, fMediaUrl, rotationAngle, mMediasCache);
                                                            }

                                                        } catch (Exception e) {
                                                            Log.e(LOG_TAG, "sendMedias failed " + e.getLocalizedMessage());
                                                        }
                                                    }
                                                }

                                                mVectorMessageListFragment.uploadImageContent(fThumbnailURL, fMediaUrl, fFilename, fMimeType);
                                            }
                                        }
                                    });
                                }
                            }

                            // default behaviour
                            if ((!isManaged) && (null != mediaUrl)) {
                                final String fMediaUrl = mediaUrl;
                                final String fMimeType = mimeType;
                                final boolean isVideo = ((null != fMimeType) && fMimeType.startsWith("video/"));
                                final String fThumbUrl = isVideo ? mVectorMessageListFragment.getVideoThumbailUrl(fMediaUrl) : null;

                                mVectorRoomActivity.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (isVideo) {
                                            mVectorMessageListFragment.uploadVideoContent(fMediaUrl, fThumbUrl, null, fMimeType);
                                        } else {
                                            mVectorMessageListFragment.uploadFileContent(fMediaUrl, fMimeType, fFilename);
                                        }
                                    }
                                });
                            }

                        }

                        mVectorRoomActivity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                handlerThread.quit();
                                mVectorRoomActivity.setProgressVisibility(View.GONE);
                            }
                        });
                    }
                });
            }
        };

        Thread t = new Thread(r);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }


    //================================================================================
    // Image resizing
    //================================================================================

    /**
     * Class storing the image information
     */
    private class ImageSize {
        public final int mWidth;
        public final int mHeight;

        public ImageSize(int width, int height) {
            mWidth = width;
            mHeight = height;
        }
    }

    /**
     * Offer to resize the image before sending it.
     */
    private void resizeMediaAndSend() {
        if (null != mPendingThumbnailUrl) {
            boolean sendMedia = true;

            // check if the media could be resized
            if ("image/jpeg".equals(mPendingMimeType)) {

                System.gc();
                FileInputStream imageStream;

                try {
                    Uri uri = Uri.parse(mPendingMediaUrl);
                    final String filename = uri.getPath();

                    final int rotationAngle = ImageUtils.getRotationAngleForBitmap(mVectorRoomActivity, uri);

                    imageStream = new FileInputStream(new File(filename));

                    int fileSize = imageStream.available();

                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    options.outWidth = -1;
                    options.outHeight = -1;

                    // retrieve the image size
                    try {
                        BitmapFactory.decodeStream(imageStream, null, options);
                    } catch (OutOfMemoryError e) {
                        Log.e(LOG_TAG, "Onclick BitmapFactory.decodeStream : " + e.getMessage());
                    }

                    final ImageSize fullImageSize = new ImageSize(options.outWidth, options.outHeight);

                    imageStream.close();

                    int maxSide = (fullImageSize.mHeight > fullImageSize.mWidth) ? fullImageSize.mHeight : fullImageSize.mWidth;

                    // can be rescaled ?
                    if (maxSide > SMALL_IMAGE_SIZE) {
                        ImageSize largeImageSize = null;

                        int divider = 2;

                        if (maxSide > LARGE_IMAGE_SIZE) {
                            largeImageSize = new ImageSize((fullImageSize.mWidth + (divider - 1)) / divider, (fullImageSize.mHeight + (divider - 1)) / divider);
                            divider *= 2;
                        }

                        ImageSize mediumImageSize = null;

                        if (maxSide > MEDIUM_IMAGE_SIZE) {
                            mediumImageSize = new ImageSize((fullImageSize.mWidth + (divider - 1)) / divider, (fullImageSize.mHeight + (divider - 1)) / divider);
                            divider *= 2;
                        }

                        ImageSize smallImageSize = null;

                        if (maxSide > SMALL_IMAGE_SIZE) {
                            smallImageSize = new ImageSize((fullImageSize.mWidth + (divider - 1)) / divider, (fullImageSize.mHeight + (divider - 1)) / divider);
                        }

                        FragmentManager fm = mVectorRoomActivity.getSupportFragmentManager();
                        ImageSizeSelectionDialogFragment fragment = (ImageSizeSelectionDialogFragment) fm.findFragmentByTag(TAG_FRAGMENT_IMAGE_SIZE_DIALOG);

                        if (fragment != null) {
                            fragment.dismissAllowingStateLoss();
                        }

                        final ArrayList<String> textsList = new ArrayList<>();
                        final ArrayList<ImageSize> sizesList = new ArrayList<>();

                        textsList.add(mVectorRoomActivity.getString(R.string.compression_opt_list_original) + ": " + android.text.format.Formatter.formatFileSize(mVectorRoomActivity, fileSize) + " (" + fullImageSize.mWidth + "x" + fullImageSize.mHeight + ")");
                        sizesList.add(fullImageSize);

                        if (null != largeImageSize) {
                            int estFileSize = largeImageSize.mWidth * largeImageSize.mHeight * 2 / 10 / 1024 * 1024;

                            textsList.add(mVectorRoomActivity.getString(R.string.compression_opt_list_large) + ": " + android.text.format.Formatter.formatFileSize(mVectorRoomActivity, estFileSize) + " (" + largeImageSize.mWidth + "x" + largeImageSize.mHeight + ")");
                            sizesList.add(largeImageSize);
                        }

                        if (null != mediumImageSize) {
                            int estFileSize = mediumImageSize.mWidth * mediumImageSize.mHeight * 2 / 10 / 1024 * 1024;

                            textsList.add(mVectorRoomActivity.getString(R.string.compression_opt_list_medium) + ": " + android.text.format.Formatter.formatFileSize(mVectorRoomActivity, estFileSize) + " (" + mediumImageSize.mWidth + "x" + mediumImageSize.mHeight + ")");
                            sizesList.add(mediumImageSize);
                        }

                        if (null != smallImageSize) {
                            int estFileSize = smallImageSize.mWidth * smallImageSize.mHeight * 2 / 10 / 1024 * 1024;

                            textsList.add(mVectorRoomActivity.getString(R.string.compression_opt_list_small) + ": " + android.text.format.Formatter.formatFileSize(mVectorRoomActivity, estFileSize) + " (" + smallImageSize.mWidth + "x" + smallImageSize.mHeight + ")");
                            sizesList.add(smallImageSize);
                        }

                        String[] stringsArray = new String[textsList.size()];

                        final AlertDialog.Builder alert = new AlertDialog.Builder(mVectorRoomActivity);
                        alert.setTitle(mVectorRoomActivity.getString(im.vector.R.string.compression_options));
                        alert.setSingleChoiceItems(textsList.toArray(stringsArray), -1, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                final int fPos = which;

                                mImageQualityPopUpInProgress = false;
                                mImageSizesListDialog.dismiss();

                                mVectorRoomActivity.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        mVectorRoomActivity.setProgressVisibility(View.VISIBLE);

                                        Thread thread = new Thread(new Runnable() {
                                            @Override
                                            public void run() {
                                                try {
                                                    // pos == 0 -> original
                                                    if (0 != fPos) {
                                                        FileInputStream imageStream = new FileInputStream(new File(filename));

                                                        ImageSize imageSize = sizesList.get(fPos);
                                                        InputStream resizeBitmapStream = null;

                                                        try {
                                                            resizeBitmapStream = ImageUtils.resizeImage(imageStream, -1, (fullImageSize.mWidth + imageSize.mWidth - 1) / imageSize.mWidth, 75);
                                                        } catch (OutOfMemoryError ex) {
                                                            Log.e(LOG_TAG, "Onclick BitmapFactory.createScaledBitmap : " + ex.getMessage());
                                                        } catch (Exception e) {
                                                            Log.e(LOG_TAG, "Onclick BitmapFactory.createScaledBitmap failed : " + e.getMessage());
                                                        }

                                                        if (null != resizeBitmapStream) {
                                                            String bitmapURL = mMediasCache.saveMedia(resizeBitmapStream, null, "image/jpeg");

                                                            if (null != bitmapURL) {
                                                                mPendingMediaUrl = bitmapURL;
                                                            }

                                                            resizeBitmapStream.close();
                                                        }
                                                    }

                                                    // try to apply exif rotation
                                                    if (0 != rotationAngle) {
                                                        // rotate the image content
                                                        ImageUtils.rotateImage(mVectorRoomActivity, mPendingMediaUrl, rotationAngle, mMediasCache);
                                                    }
                                                } catch (Exception e) {
                                                    Log.e(LOG_TAG, "Onclick " + e.getMessage());
                                                }

                                                mVectorRoomActivity.runOnUiThread(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        mVectorRoomActivity.setProgressVisibility(View.GONE);
                                                        mVectorMessageListFragment.uploadImageContent(mPendingThumbnailUrl, mPendingMediaUrl, mPendingFilename, mPendingMimeType);
                                                        mPendingThumbnailUrl = null;
                                                        mPendingMediaUrl = null;
                                                        mPendingMimeType = null;
                                                        mPendingFilename = null;
                                                        mVectorRoomActivity.manageSendMoreButtons();
                                                    }
                                                });
                                            }
                                        });

                                        thread.setPriority(Thread.MIN_PRIORITY);
                                        thread.start();
                                    }
                                });
                            }
                        });

                        mImageQualityPopUpInProgress = true;
                        mImageSizesListDialog = alert.show();
                        mImageSizesListDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface dialog) {
                                mImageQualityPopUpInProgress = false;
                                mImageSizesListDialog = null;
                            }
                        });

                        sendMedia = false;
                    }

                } catch (Exception e) {
                    Log.e(LOG_TAG, "Onclick " + e.getMessage());
                }
            }

            if (sendMedia) {
                mVectorMessageListFragment.uploadImageContent(mPendingThumbnailUrl, mPendingMediaUrl, mPendingFilename, mPendingMimeType);
                mPendingThumbnailUrl = null;
                mPendingMediaUrl = null;
                mPendingMimeType = null;
                mPendingFilename = null;
                mVectorRoomActivity.manageSendMoreButtons();
            }
        }
    }


}