# Connecting Nest View to your cameras

Nest View talks to Google's **Smart Device Management (SDM) API** through your own *Device Access*
project. You do this once; afterwards the app opens straight into the cameras.

You need: the Google account that owns your Nest cameras (in the Google Home app), a one-time
US$5 Device Access registration fee, and about 20 minutes.

## 1. Google Cloud project

1. Open <https://console.cloud.google.com/> and create a project (any name, e.g. "Nest View").
2. **APIs & Services → Library**: enable **Smart Device Management API**.
3. **Google Auth Platform → Branding / Audience** (the OAuth consent screen):
   - User type **External**, app name "Nest View", your email as support and developer contact.
   - Google only lets you publish once **Branding** also has an **application home page** and an
     **application privacy policy link**. Host [PRIVACY.md](PRIVACY.md) somewhere public (a
     public GitHub gist, repo or GitHub Pages site works) and use that address for both. If Google
     asks for it, add that site's domain (for example `github.com`) under **Authorized domains**.
     Leave the **logo** empty: uploading one makes Google require a verification review.
   - Add your Google account (and your partner's) as **test users**.
   - Then press **Publish app** so the status is **In production**. While the app is in
     "Testing", Google expires the sign-in after **7 days**. An unverified app is fine for
     personal use: Google shows a "Google hasn't verified this app" warning, which you can pass
     with *Advanced → Go to Nest View*.

## 2. OAuth client (Android)

1. Get the SHA-1 of the key that signs the app. For debug and the provided release setup this is
   the Android debug key:

   ```bash
   keytool -list -v -keystore "%USERPROFILE%\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
   ```

2. **Google Auth Platform → Clients → Create client**: type **Android**, package name
   `app.nestview`, the SHA-1 from above.
3. Open the new client, expand **Advanced settings**, tick **Enable custom URI scheme**, save.
4. Copy the client ID (`1234-abc.apps.googleusercontent.com`).

## 3. Device Access project

1. Open <https://console.nest.google.com/device-access>, accept the terms and pay the fee.
2. **Create project**, paste the OAuth client ID. Skip "events" (Pub/Sub) for now.
3. Copy the **Project ID** (a UUID).

## 4. Build with your settings

Add to `local.properties` in the project root (it is not committed):

```properties
SDM_PROJECT_ID=your-device-access-project-id
OAUTH_CLIENT_ID=1234-abc.apps.googleusercontent.com
```

Build and install:

```bash
gradlew.bat installDebug
```

## 5. First start

Tap **Connect Google Home**. Google shows its Nest permissions page: pick your home, allow
**access to the cameras** you want to watch, and continue. The app lists your cameras and opens
the monitor. That's it.

## If Google rejects the sign-in redirect

Some accounts or consoles don't accept the Android custom-scheme redirect. Use a Web client and a
paste step instead:

1. Create a second OAuth client of type **Web application** with authorized redirect URI
   `https://www.google.com`.
2. In the Device Access console, change the project's OAuth client ID to this Web client.
3. In `local.properties` (or in the app under *Device Access settings*):

   ```properties
   OAUTH_CLIENT_ID=5678-def.apps.googleusercontent.com
   OAUTH_CLIENT_SECRET=GOCSPX-...
   OAUTH_REDIRECT_URI=https://www.google.com
   ```

4. **Connect Google Home** now opens the browser. When Google lands on google.com, copy that
   page's full address (it contains `code=`) and paste it into the app.

## More phones and tablets

Install the same APK on each device and connect once on each. Every device streams directly from
Google, so they don't depend on each other. A Device Access sandbox project allows up to 25
Google accounts, 5 per home.

## Which cameras work

WebRTC cameras: Nest Cam (battery), Nest Cam (wired, 2nd gen), Nest Cam with floodlight,
Nest Doorbell (battery / wired), and older Nest Cams that were moved to the Google Home app.
Cameras that only offer RTSP (Nest Cam IQ, Nest Hub Max, Nest Hello, older cams still in the Nest
app) are listed as skipped. The 2025 3rd-generation cameras are not available through the SDM API.
