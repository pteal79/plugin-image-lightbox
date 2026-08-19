# ImageLightbox Plugin for NativePHP Mobile

Display images (`jpg`, `jpeg`, `png`, `heic`) in a full-screen native lightbox overlay above the running app UI.

**Features**

- Native full-screen modal on both iOS and Android
- **Swipeable galleries** — pass a dataset, open at any index, swipe between images with native paging animation
- Live dataset updates while the lightbox is open (`update()`), with an `n / total` counter
- Optional per-image captions (`'title' => true`) that fade away while the reader zooms in
- Pinch-to-zoom (up to 5×), double-tap to zoom, and pan after zooming
- Swipe down to dismiss, with the image tracking the finger and the backdrop fading to the app behind
- Aspect-fit display by default
- Loads local file paths and remote URLs (with WebView session cookie injection for authenticated endpoints)
- Optional **Edit**, **Markup**, **Share**, and **Delete** action buttons
- Native share sheet for both local files and remote images
- `EditPressed`, `MarkupPressed`, `DeletePressed`, and `ClosePressed` events with `imageId` and `index` payload
- Icon-based toolbar at the top of the screen with dark-background buttons for legibility
- Safe-area aware controls; dismiss animation
- Graceful error states (invalid URL, missing file, decode failure)

---

## Installation

```bash
# 1. Install the package
composer require pteal79/plugin-image-lightbox

# 2. Publish the plugins provider (first time only)
php artisan vendor:publish --tag=nativephp-plugins-provider

# 3. Register the plugin (adds the service provider to NativePluginsServiceProvider)
php artisan native:plugin:register pteal79/plugin-image-lightbox

# 4. Verify registration
php artisan native:plugin:list
```

### Local development (path repository)

Add to your app's `composer.json`:

```json
{
    "repositories": [
        {
            "type": "path",
            "url": "./packages/pteal79/plugin-image-lightbox"
        }
    ]
}
```

Then run `composer require pteal79/plugin-image-lightbox`.

---

## Requirements

### Android

| Requirement | Detail |
|---|---|
| Permission | `android.permission.INTERNET` (remote URLs) — added automatically via `nativephp.json` |
| Gradle dependency | `androidx.viewpager2:viewpager2` (gallery paging) — added automatically via `nativephp.json` |
| FileProvider | The host app must have a `FileProvider` configured with authority `${applicationId}.provider` for the **Share** feature to work. NativePHP Mobile typically configures this by default. |
| HEIC support | Android 9 (API 28)+ via `ImageDecoder`. Older devices fall back to `BitmapFactory`; HEIC may not decode on API < 28. |

### iOS

No additional permissions or Info.plist entries are required. HEIC is supported natively via `UIImage`.

---

## Usage

### PHP — Livewire / Blade

```php
use Nativephp\ImageLightbox\Facades\ImageLightbox;

// Remote URL — minimal
ImageLightbox::show([
    'url' => 'https://example.com/photo.jpg',
]);

// Local file — minimal
ImageLightbox::show([
    'local' => '/var/mobile/.../Documents/app/storage/app/public/photo.jpg',
]);

// Full options
ImageLightbox::show([
    'url'     => 'https://example.com/photo.heic',
    'imageId' => '550e8400-e29b-41d4-a716-446655440000',
    'edit'    => true,
    'markup'  => true,
    'share'   => true,
    'delete'  => true,
]);
```

### Parameters

| Parameter | Type     | Default | Description |
|-----------|----------|---------|-------------|
| `url`     | `string` | `null`  | Remote image URL (`http`/`https`). Supported formats: `jpg`, `jpeg`, `png`, `heic`, `webp`. |
| `local`   | `string` | `null`  | Absolute local file path to an image on the device. |
| `imageId` | `string` | `null`  | Optional identifier included in all event payloads. |
| `edit`    | `bool`   | `false` | Show an **Edit** button in the toolbar. |
| `markup`  | `bool`   | `false` | Show a **Markup** button in the toolbar. |
| `share`   | `bool`   | `false` | Show a **Share** button that opens the native share sheet. |
| `delete`  | `bool`   | `false` | Show a **Delete** button in the toolbar. |

Either `url` or `local` is required. If neither is provided the call is a no-op.

---

## Galleries

Pass a dataset and the lightbox becomes a swipeable gallery. Each row is shaped
`index`, `uuid`, `file`, `text`:

```php
use Pteal79\ImageLightbox\Facades\ImageLightbox;

$data = [
    ['index' => 0, 'uuid' => 'a1b2…', 'file' => 'https://example.com/one.jpg',   'text' => 'Front elevation'],
    ['index' => 1, 'uuid' => 'c3d4…', 'file' => '/var/mobile/…/two.jpg',        'text' => 'Rear elevation'],
    ['index' => 2, 'uuid' => 'e5f6…', 'file' => 'php://storage/photos/three.png'],
];

$gallery = ImageLightbox::gallery($data);

$gallery->config([
    'edit'   => true,
    'markup' => true,
    'share'  => true,
    'delete' => true,
    'title'  => true,   // show each row's `text` as a caption
]);

$gallery->open(1);   // open on the row whose `index` is 1
$gallery->start();   // open on the first row
$gallery->last();    // open on the last row
```

Every method is chainable, so the whole thing reads as one statement:

```php
ImageLightbox::gallery($data)->config(['share' => true])->start();
```

`ImageLightbox::gallery()` returns the same instance for the lifetime of the
request, so a dataset set in one place can be reused in another:

```php
ImageLightbox::gallery()->update($freshData);
```

### Dataset rows

| Key     | Type     | Required | Description |
|---------|----------|----------|-------------|
| `index` | `int`    | no       | Orders the gallery, and is what `open()` looks up. Defaults to the row's position. |
| `uuid`  | `string` | no       | Sent back as `imageId` on every event, and used to keep the reader on the same image across an `update()`. |
| `file`  | `string` | **yes**  | A remote URL (`http://`, `https://`, `php://`) or an absolute local path. Rows without one are dropped. |
| `text`  | `string` | no       | Caption shown beneath the image — only when `config(['title' => true])`. |

Rows are sorted by `index` before being sent to native. A `file` carrying a
URL scheme becomes a remote fetch; anything else is treated as an on-device
path (`file://` is accepted and stripped to a plain path).

### Methods

| Method            | Description |
|-------------------|-------------|
| `gallery($data)`  | Sets the dataset and returns the gallery instance. |
| `config($array)`  | Toggles the `edit`, `markup`, `share`, `delete`, and `title` options. |
| `open($index)`    | Opens on the row carrying that `index` value, falling back to treating it as a zero-based position. |
| `start()`         | Opens on the first row. |
| `last()`          | Opens on the last row. |
| `update($data)`   | Replaces the dataset, pushing it into an open lightbox. |
| `setItems($data)` | Replaces the dataset without touching an open lightbox. |
| `close()`         | Dismisses an open lightbox. |
| `items()`         | The normalised dataset as handed to native. |
| `count()`         | Number of rows. |
| `isEmpty()`       | Whether the dataset is empty. |
| `settings()`      | The current toolbar configuration. |

Opening an empty gallery is a no-op — `start()`, `last()`, and `open()` all
return without calling native.

### Config options

| Key      | Default | Description |
|----------|---------|-------------|
| `edit`   | `false` | Show an **Edit** button. |
| `markup` | `false` | Show a **Markup** button. |
| `share`  | `false` | Show a **Share** button. |
| `delete` | `false` | Show a **Delete** button. |
| `title`  | `false` | Show each row's `text` as a caption. |

### Captions

With `'title' => true`, each row's `text` appears near the bottom of the screen —
centred, white on a semi-transparent rounded background. The caption is tied to
the default zoom level: it fades out the moment the reader pinches or double-taps
to zoom in, and fades back when they return to the fitted view or swipe to the
next image. Rows with no `text` simply show nothing.

Left at its default of `false`, no caption is drawn at all, whatever the dataset
carries.

### Updating an open gallery

`update()` swaps the dataset underneath a lightbox that is already on screen.
The reader stays on the same image where its `uuid` survived the update;
otherwise the position is clamped into the new range. Passing an empty dataset
dismisses the lightbox.

This is what makes **Delete** work without leaving the gallery — in gallery
mode the Delete button dispatches `DeletePressed` and *stays open*, so your
handler removes the row and calls `update()`:

```php
use Native\Mobile\Attributes\OnNative;
use Pteal79\ImageLightbox\Events\DeletePressed;

#[OnNative(DeletePressed::class)]
public function handleDelete(?string $imageId = null, ?int $index = null): void
{
    Photo::where('uuid', $imageId)->delete();

    ImageLightbox::gallery()->update($this->photoRows());
}
```

Deleting the last remaining image leaves an empty dataset, which closes the
lightbox. In single-image mode (`show()`, or a one-row gallery) Delete keeps
its original behaviour and dismisses immediately.

---

## Events

Each event carries the `imageId` — the `imageId` passed to `::show()`, or the row's `uuid` in a gallery — plus the row's `index`. Both are optional constructor arguments, so a handler may take either, both, or neither.

Events are dispatched after the lightbox dismisses. The one exception is `DeletePressed` in a gallery of more than one image, which fires while the lightbox stays open (see [Updating an open gallery](#updating-an-open-gallery)).

### `ClosePressed`

Fired when the user taps the **close (✕)** button.

```php
use Native\Mobile\Attributes\OnNative;
use Pteal79\ImageLightbox\Events\ClosePressed;

#[OnNative(ClosePressed::class)]
public function handleClose(?string $imageId = null): void
{
    // lightbox has been dismissed
}
```

### `EditPressed`

Fired when the user taps the **Edit** button (only available when `edit: true`).

```php
use Native\Mobile\Attributes\OnNative;
use Pteal79\ImageLightbox\Events\EditPressed;

#[OnNative(EditPressed::class)]
public function handleEdit(?string $imageId = null): void
{
    // open your edit UI here
}
```

### `MarkupPressed`

Fired when the user taps the **Markup** button (only available when `markup: true`).

```php
use Native\Mobile\Attributes\OnNative;
use Pteal79\ImageLightbox\Events\MarkupPressed;

#[OnNative(MarkupPressed::class)]
public function handleMarkup(?string $imageId = null): void
{
    //
}
```

### `DeletePressed`

Fired when the user taps the **Delete** button (only available when `delete: true`).

```php
use Native\Mobile\Attributes\OnNative;
use Pteal79\ImageLightbox\Events\DeletePressed;

#[OnNative(DeletePressed::class)]
public function handleDelete(?string $imageId = null): void
{
    // perform your delete logic here
}
```

### Event payload summary

| Event | Property | Type | Description |
|-------|----------|------|-------------|
| `ClosePressed`  | `imageId` / `index` | `string\|null` / `int\|null` | The `imageId` passed to `::show()`, or the gallery row's `uuid` and `index` |
| `EditPressed`   | `imageId` / `index` | `string\|null` / `int\|null` | As above |
| `MarkupPressed` | `imageId` / `index` | `string\|null` / `int\|null` | As above |
| `DeletePressed` | `imageId` / `index` | `string\|null` / `int\|null` | As above |

---

## JavaScript (Vue / React / Inertia)

```javascript
import { ImageLightbox, Events } from '@pteal79/plugin-image-lightbox';
import { on, off } from '@nativephp/native';

// Show the lightbox
await ImageLightbox.show({
    url:     'https://example.com/photo.jpg',
    imageId: 'abc-123',
    edit:    true,
    delete:  true,
    share:   true,
});

// Listen for events using the exported constants (avoids typos)
const onDelete = ({ imageId }) => console.log('Delete pressed for', imageId);
on(Events.DeletePressed, onDelete);

const onClose = ({ imageId }) => console.log('Closed for', imageId);
on(Events.ClosePressed, onClose);

// Clean up
off(Events.DeletePressed, onDelete);
off(Events.ClosePressed, onClose);
```

Galleries mirror the PHP API:

```javascript
const gallery = ImageLightbox.gallery(rows).config({ share: true, delete: true });

await gallery.start();       // first image
await gallery.open(3);       // the row whose `index` is 3
await gallery.last();        // last image
await gallery.update(rows);  // swap the dataset in place
await gallery.close();       // dismiss
```

Available event constants:

```javascript
Events.EditPressed   // 'Pteal79\\ImageLightbox\\Events\\EditPressed'
Events.MarkupPressed // 'Pteal79\\ImageLightbox\\Events\\MarkupPressed'
Events.DeletePressed // 'Pteal79\\ImageLightbox\\Events\\DeletePressed'
Events.ClosePressed  // 'Pteal79\\ImageLightbox\\Events\\ClosePressed'
```

---

## Toolbar

The toolbar sits at the top of the screen. Each button is a white SF Symbol (iOS) or Canvas-drawn icon (Android) on a semi-transparent dark background for legibility over any image. The close button is always present on the right; action buttons appear on the left in the order: Edit → Markup → Share → Delete. In a gallery of more than one image, an `n / total` counter sits in the centre.

---

## Swipe to dismiss

Dragging down on the image closes the lightbox. The image follows the finger and
shrinks slightly while the backdrop and toolbar fade out, revealing the app
behind. Releasing past roughly a fifth of the screen height — or flicking down
quickly — completes the dismissal; anything short of that springs back.

The gesture is only armed at the default zoom level. Once the reader has zoomed
in, a downward drag pans the image instead, as you would expect.

A swipe dismissal fires `ClosePressed`, exactly like tapping the close button, so
handlers need no special case.

---

## Share behaviour

- **Local file** — shared directly via the native share sheet.
- **Remote URL** — the image is downloaded to a temporary cache file first, then shared. If the image was already loaded for display, the cached copy is reused (no second download).
- Share failures (network error, missing file) surface a toast / alert — the lightbox remains open.

---

## Remote URL authentication

When loading a remote URL the plugin injects the current WebView session cookies into the `URLSession` / `HttpURLConnection` request automatically, so images served behind a Laravel session-authenticated route will load correctly.

---

## Limitations

- HEIC decoding on Android requires API 28+. On older devices the image may fail to render; a graceful error message is shown.
- The plugin presents over the current top-most view controller / activity. Ensure no other full-screen modal is already covering the app when calling `::show()`.
- The Edit, Markup, and Delete buttons are UI affordances only — the plugin dispatches an event and dismisses. Your application is responsible for the resulting action.
- Very large images (>20 MP) may be slow to decode on low-end Android devices. Consider resizing on the server before passing to the lightbox.
- Only one lightbox can be on screen at a time. Opening a second one dismisses the first.
- Decoded images are cached for the life of a single lightbox session, so swiping back and forth never re-downloads. The cache is dropped when the lightbox closes.
- The Share feature on Android requires the host app to have a `FileProvider` registered with the authority `${applicationId}.provider`. NativePHP Mobile configures this by default, but if you see a `FileUriExposedException` you may need to add/adjust the provider in your app's `AndroidManifest.xml`.

---

## License

MIT
