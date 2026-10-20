# ScanPro PDF

A native Android starter app for document capture and PDF utilities.

## Included in this first build
- Capture a document image with the camera and save it as a PDF.
- Select multiple gallery images and combine them into a multi-page PDF.
- Extract printed text from an image with on-device ML Kit OCR; copy the recognized text.
- Save generated PDFs in the app's private Documents/ScanPro folder.

## Planned next
- PDF merge and split with page selection.
- PDF viewer, page reorder/rotate, crop and filters.
- Higher-resolution camera capture and document edge detection.
- Share/export flow and accessibility polish.

## Build an APK
1. Open the **Actions** tab in this repository.
2. Select **Build ScanPro PDF APK**.
3. Tap **Run workflow** (or wait for the workflow triggered by a code push).
4. Open the completed run and download the **ScanPro-PDF-debug-apk** artifact.
5. Extract the ZIP and install `app-debug.apk` on your Android phone. Android may ask you to allow installation from that source.

This workflow builds a **debug APK** for testing. A Play Store release needs a signed release build, testing, a privacy policy, and Play Console setup.

## Tech
- Kotlin and Android SDK
- Android PdfDocument API
- Google ML Kit Text Recognition (Latin)

## Privacy
The app does not currently upload documents to a server. Selected images are processed on-device, and generated PDFs are saved in the app's private external-files directory.
