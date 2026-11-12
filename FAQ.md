# SmartTube LOCAL FAQ

New here? The [README](README.md) is the quick introduction. This page covers setup and the practical details for SmartTube LOCAL. 

## Do I need a Google account?

**NO!** You can create and use local profiles, browse the Local Library, play local videos, and use local playlists without signing in to Google. Additionally, **AUTO** (online YouTube) does not require an account (as playlists, subscriptions etc) are saved locally. Furthermore, **AUTO** allows you to combine both local videos and "online" videos in one interface. 

## How does search work?

In **LOCAL** mode, it searches across titles and meta-data from downloaded videos. In **AUTO** it searches online AND presents Local results in separate row.

## What is a local profile?

Each profile has its own settings, subscriptions and playlists. In Settings → Local Library, you can turn library access on or off for that profile. Profiles share the same SMB archive, but can maintain separate "channels", "subscriptions" etc 

Profiles stay on the device and can be protected with SmartTube PIN settings. SMB passwords are encrypted with Android Keystore.

## What do AUTO and LOCAL ONLY do?

- **AUTO - YouTube + Local Library:** use the normal online YouTube sections and the local archive. If YouTube becomes unavailable, SmartTube LOCAL can show local content.
- **LOCAL ONLY - Local Library without YouTube:** use local archive browsing and playback, without YouTube browse/play requests from the app.

Nb: LOCAL ONLY is an app mode, not a network firewall. Still, you can disable internet access entirely and play your videos solely from LAN / SMB. SmarttubeLocal does NOT require internet access to play locally stored videos.  

## How do I connect an SMB archive?

The TV and SMB server need to be reachable on the same local network. Open **Settings → Local Library** and configure:

1. **Allow Local Library for this profile** - switch it on for the active profile.
2. **Content source** - choose AUTO or LOCAL ONLY.
3. **SMB server** - the server name or LAN address.
4. **Share** - the SMB share name, not the folder path inside it.
5. **Archive folder** - the folder within the share that contains the archive and `_catalogue` directory.
6. **SMB username** and **Replace SMB password** - enter an account that can read the share.

Choose **Refresh catalogue from SMB** after your catalogue publisher has published a new revision. Choose **Test and scan archive** to test the connection and read the archive. These actions read the published catalogue; they do not create or publish one.

Nb: Local Library requires Android 6.0 or newer because the app protects SMB credentials with Android Keystore encryption. 

### What does the archive need?

SmartTube LOCAL reads a catalogue from the archive folder. The catalogue has a pointer file and one or more revision folders. The pointer tells the app which complete revision to read.

Each video entry points to its media file and may also point to its metadata and thumbnail. The catalogue publisher must finish writing a revision before updating the pointer. This prevents the app from being sent to an incomplete catalogue.

For example:

```text
Archive/
├── Channel Name/
│   ├── A video title [YouTubeVideoID].mp4
│   ├── A video title [YouTubeVideoID].info.json
│   └── A video title [YouTubeVideoID].webp
└── _catalogue/
    ├── pointer.json
    └── v123/
        ├── library.json
        └── channels/
```

The catalogue stores the actual file paths and gets the channel name from the metadata. Put the original YouTube video ID in the media filename, and keep its `.info.json` file beside it. Thumbnails are optional; local thumbnail files let cards show artwork without fetching it from the Internet.

The current catalogue producer recognizes **MP4 files only**. For broad device compatibility, use MP4 with H.264 video and AAC audio.

## Can I use MeTube or yt-dlp to auto-magic this?

**Hell yes you can**

[MeTube](https://github.com/alexta69/metube) is a free, web-based video downloader. Give it a YouTube link, and it downloads the video for you.

SmartTube LOCAL plays those videos straight from a shared folder on your home network.

**There's just one small catch.**

SmartTube LOCAL needs a list of what's in your video archive - a *catalogue*. Without it, the app doesn't know what videos you've downloaded.

Our little [catalogue publisher](goodies/catalogue_publisher.py) creates that list.

### How do I get started?

1. Install MeTube using the instructions on its [official GitHub page](https://github.com/alexta69/metube).
2. Set MeTube to save MP4 videos, their `.info.json` metadata files and thumbnails in your shared video folder.
3. Download the SmartTube LOCAL catalogue publisher.
4. Run the publisher against your video folder.
5. Open SmartTube LOCAL, configure your SMB share, and select **Refresh catalogue from SMB**.

**That's it. Your videos should appear in the Local Library.**

### How do I run the publisher?

You'll need Python installed on a computer that can access your video folder.

For example, on Windows:

```powershell
python goodies\catalogue_publisher.py --root "D:\MediaShare\SmartTubeArchive" --once
```

Replace the example folder with the location of your own video archive.

The publisher creates the catalogue in that folder. It doesn't move, copy or download your videos.

### Do I have to do this every time?

Only when your archive changes.

You can run the publisher yourself, or use its `--watch` option to let it check the archive automatically.

**Already run MeTube on a home server?**

You can also configure MeTube to trigger a catalogue update after each completed download. That's how my own setup works.

This is optional, and it requires some additional setup. MeTube doesn't create SmartTube LOCAL catalogues by itself.

For MeTube installation and configuration, see the [official MeTube documentation](https://github.com/alexta69/metube). We make the TV app, not the downloader!

## Why are upload dates or descriptions missing?

Local metadata comes from the archive catalogue and the `.info.json` file referenced by that catalogue. Check that:

- the sidecar is beside the matching media file and has the same basename;
- the sidecar contains the original `upload_date` or description;
- the active catalogue revision has `uploadDate` and a correct `infoPath` for that video; and
- the TV has refreshed the catalogue after the new revision was published.

The date uses the original YouTube upload date, not the file's copy or download date. 

## How do Local Library search and Recommended Today work?

Local Library search checks the current local catalogue for video titles and channel names, and includes matching local playlists. It does not search YouTube. **Recommended Today** is a locally prepared daily selection from the catalogue. If either is empty, first confirm that Local Library is enabled for the active profile and that the TV has a valid, populated catalogue.

## How do local playlists work?

The Playlists section displays playlists published in the archive catalogue. Playlist titles, source order and video membership are read from the catalogue; missing videos can be reported as missing entries. Playlist artwork is selected from the playlist's available local video artwork. No separate media copy is made for a playlist.

## Can I import my YouTube playlists?

**Yep! No need to recreate years of carefully curated playlists.** SmartTube LOCAL can import them from Google Takeout.

It's fairly painless, although Google will make you jump through a few hoops first.

### First, grab your playlists from Google.

1. Head to [Google Takeout](https://takeout.google.com/).
2. Hit **Deselect all**.
3. Find **YouTube and YouTube Music** and tick it.
4. Open **All YouTube data included**.
5. Untick everything except **playlists**.
6. Create your export and download the ZIP file.

Done. 

### And then? 

You've got your Google Takeout ZIP. Good. Now we need to get it from your phone or computer onto the TV.

#### Android phone? Use Send Files to TV.

Grab **[Send Files to TV from Google Play](https://play.google.com/store/apps/details?id=com.yablio.sendfilestotv)**. It's free to install and transfers files over your home Wi-Fi.

1. Install **Send Files to TV** on your Android phone and Android TV.
2. Make sure both devices are on the same home network.
3. Open the app on your TV and select **Receive**.
4. Open the app on your phone and select **Send**.
5. Find your Google Takeout ZIP and select your TV.
6. Done! Your ZIP should now be on your TV, usually in the Downloads folder.

#### iPhone? 

Try **LocalSend** instead. It's free, open-source and works with iPhones, Android TVs and computers.

You'll need it on both devices:

- **[LocalSend for iPhone](https://apps.apple.com/au/app/localsend/id1661733229)**
- **[LocalSend for Android TV](https://play.google.com/store/apps/details?id=org.localsend.localsend_app)**

Then: 

1. Install LocalSend on your iPhone and Android TV.
2. Make sure both devices are on the same home network.
3. Open LocalSend on both devices.
4. On your iPhone, select **Send**, then choose the ZIP from the Files app.
5. Select your TV from the list of nearby devices.
6. Accept the transfer on your TV.

**Nb:** LocalSend also works on Windows, macOS and Linux. You can grab those versions from [localsend.org](https://localsend.org/).

### Where did my file go?

Most transfers end up in your TV's Downloads folder.

Some Android TV file pickers are a little anal about which folders they'll show.

If SmartTube LOCAL can't find your ZIP, use a TV file manager to move it here:

`Android/media/smarttube`

**Nb: And don't unzip it!** SmartTube LOCAL handles that bit for you.

### Time to import!

1. Open SmartTube LOCAL and select the profile that should receive your playlists.
2. Head to **Settings → Backup & Restore → Import Google Takeout playlists**.
3. Find your ZIP file and select it.
4. Let SmartTube LOCAL do its thing.

**Got hundreds of playlists?** The first import might take *several* minutes. The screen may look idle while SmartTube LOCAL fetches video details.

Give it some time (upto 10 mins) before you assume something's broken.

**Nb:** if a brand-new profile refuses its first import, create a small playlist manually and try again.

And that's it. Your YouTube playlists are now part of your SmartTube LOCAL profile!

## Do I have to do this? 

No. If you don't want to import Youtube channels or playlists, you can create them directly on SmartTube LOCAL. You can populate it with LOCAL and online content, ad-hoc. 

**Nb:** Online content will NOT appear when you are in LOCAL mode. 


## Can I import my YouTube subscriptions?

**Absolutely.** You don't have to find and subscribe to every channel again.

One small catch - SmartTube wants your subscriptions in a format used by NewPipe, rather than Google's usual Takeout format.

Here's how - 

### Step 1: Get your subscriptions.

**Already use NewPipe or PipePipe?**

Great. Export your subscriptions as a JSON file and skip to Step 2.

**Coming straight from YouTube?**

1. Visit [Google Takeout](https://takeout.google.com/) and export your YouTube subscriptions.
2. Import that export into NewPipe or PipePipe.
3. Export your subscriptions from there as a JSON file.

(sorry - it's an extra step. I don't particularly like it either but upstream SmartTube had the machinery in place and it was easier to leverage what was already there). 

### Step 2: Get the file onto your TV.

Copy that JSON file to your Android TV.

I recommend this folder:

`Android/media/smarttube`

Why there? Because Android TV file pickers can be rather particular about which folders they'll let you access.

**Tip:** See above advice for **Send Files to TV** and **LocalSend** for to transferring the file from your phone or computer. 

### Step 3: Import!

1. Open SmartTube LOCAL and select your profile.
2. Head to **Settings → Backup & Restore → Import subscriptions group (GrayJay/PocketTube/NewPipe)**.
3. Select the JSON file.
4. Let SmartTube LOCAL do its thing.

### Nothing seems to be happening?

Again - don't panic. Just like playlist imports, the first subscription import can take a while (depending on the size of the import).

SmartTube LOCAL needs to fetch channel names, icons and other details. The screen may look idle while it does this.

Just...wait longer.

### What about the kids? I don't want them watching what I watch! 

Yeah - I've got kids too. Relax :)

**Subscriptions belong to individual SmartTube LOCAL profiles.**

Dad can watch questionable material about boats, tech and home-lab videos. The kids can have their own channels. Everyone gets their own subscriptions and playlists.

And nobody needs a Google account just to use their local profile.

## Does SponsorBlock work offline?

**Yep!** 

Two ways to deal with them.

### Option 1: Let SmartTube LOCAL skip them.

If your archive includes cached SponsorBlock data, SmartTube LOCAL can use it to skip sponsored segments automatically.

It works much like regular SponsorBlock, except your TV gets the information from your local archive instead of the Internet.

There's one catch: the cached data needs to match the video, including its ID and duration.

**No matching SponsorBlock data? No automatic skips.**

### Option 2: Remove the segments before they reach your TV.

This is the simpler approach.

MeTube and yt-dlp support a **Remove Sponsor Segments** option.

Enable it when you download your videos, and yt-dlp can remove the selected SponsorBlock segments from the downloaded file.

- No sponsor segments to skip.
- No SponsorBlock cache needed for those segments.
- No Internet connection required during playback.

**One thing to remember:** This changes the video file itself. Once you've removed those segments, you'll need to download the original video again if you want them back. It's on you if you start to miss the fish doing the Ad reads during Why Files episodes. 



