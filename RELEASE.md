# Making a release

1. Raise the version in `AndroidManifest.xml`: `versionCode` by one, and
   `versionName`.
2. Add a section for the version to `CHANGELOG.md`.
3. Build:

   ```sh
   export ANDROID_JAR=/path/to/android.jar
   export KEYSTORE=/path/to/your.keystore KEY_ALIAS=yourkey KS_PASS=yourpassword
   ./build.sh
   ```

4. Check the signature. The certificate digest must be the same as in every
   earlier release, or phones will refuse the update:

   ```sh
   apksigner verify --print-certs out/hopper.apk
   ```

5. Name the package after the version, for example `hopper-1.0.apk`.
6. Commit, tag and push:

   ```sh
   git add -A
   git commit -m "Release 1.0"
   git tag v1.0
   git push && git push --tags
   ```

7. On the repository page, draft a new release from the tag, paste the
   version's section of the changelog, and attach the APK.
