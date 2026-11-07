# Nest View privacy policy

*Last updated: 8 November 2026*

Nest View is an Android app that shows live video from your own Nest cameras. It is not
distributed through any app store: each copy is built and installed by the person who uses it,
connected to their own Google Cloud and Device Access project. This policy describes the app as
published at <https://github.com/dzemskov/nest-view>. If your copy was changed, ask whoever changed
it what it does differently.

## What the app accesses

When you connect your Google account, you choose which Nest cameras Nest View may use. With that
permission (Google's `sdm.service` scope) the app reads the list of those cameras, their names and
rooms, and opens live video streams from them.

## Where your data goes

- **Only to Google.** The app talks directly to Google's servers: Google sign-in
  (`accounts.google.com`, `oauth2.googleapis.com`), the Smart Device Management API
  (`smartdevicemanagement.googleapis.com`) and Google's WebRTC connection service
  (`stun.l.google.com`). Video streams come straight from Google to your device.
- **Nothing goes to the app's authors** or to whoever set up your copy. Nest View has no server of
  its own, no analytics, no advertising, no crash reporting and no tracking. No data is sold or
  shared with anyone.

## What is stored on your device

- Your Google sign-in token, encrypted with a key kept in the Android Keystore.
- The camera list and your settings (nicknames, layouts, hidden cameras).
- One small still image per camera, kept in the app's cache so the screen is not blank while the
  stream starts.

Video is not recorded. Device backup is turned off, so none of this leaves the device in a backup.

## Removing your data

- **Sign out** in the app's settings deletes the stored Google sign-in token.
- Uninstalling the app, or clearing its storage in Android settings, deletes everything it stored.
- To withdraw access on Google's side, remove Nest View at
  <https://myaccount.google.com/connections> or in the Google Home app's partner connections.

## Google API data

Nest View's use of information received from Google APIs follows the
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including the Limited Use requirements.

## Contact

For questions about your copy, write to the support email shown on Google's sign-in screen for
Nest View. For questions about the app itself, open an issue at
<https://github.com/dzemskov/nest-view/issues>.
