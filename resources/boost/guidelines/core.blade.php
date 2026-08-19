## pteal79/plugin-image-lightbox

Displays images (jpg, jpeg, png, heic) in a full-screen native lightbox overlay with pinch-to-zoom, pan, swipeable galleries, optional Edit/Markup/Share/Delete buttons, and native share-sheet support.

### Installation

```bash
composer require pteal79/plugin-image-lightbox
php artisan vendor:publish --tag=nativephp-plugins-provider
php artisan native:plugin:register pteal79/plugin-image-lightbox
php artisan native:plugin:list
```

### Single image (PHP)

Use the `ImageLightbox` facade. Pass either `url` (remote http/https/php://) or `local` (absolute device path) — at least one is required.

@verbatim
<code-snippet name="Show a lightbox" lang="php">
use Pteal79\ImageLightbox\Facades\ImageLightbox;

ImageLightbox::show([
    'url'     => 'https://example.com/photo.heic',
    'imageId' => '550e8400-e29b-41d4-a716-446655440000',
    'edit'    => true,
    'markup'  => true,
    'share'   => true,
    'delete'  => true,
]);
</code-snippet>
@endverbatim

| Parameter | Type   | Default | Description                                       |
|-----------|--------|---------|---------------------------------------------------|
| `url`     | string | `null`  | Remote URL (`http`, `https`, `php://`).           |
| `local`   | string | `null`  | Absolute local file path.                         |
| `imageId` | string | `null`  | Identifier forwarded in event payloads.           |
| `edit`    | bool   | `false` | Show an Edit button.                              |
| `markup`  | bool   | `false` | Show a Markup button.                             |
| `share`   | bool   | `false` | Show a Share button.                              |
| `delete`  | bool   | `false` | Show a Delete button.                             |

### Galleries (PHP)

Pass a dataset of `index`, `uuid`, `file`, `text` rows to get a swipeable gallery. `file` is a remote URL or an absolute local path; rows are sorted by `index`.

Config keys are all `false` by default: `edit`, `markup`, `share`, `delete` toggle toolbar buttons, and `title` shows each row's `text` as a caption near the bottom of the screen. The caption is bound to the default zoom level — it fades out while the reader zooms in and returns when they zoom back out or swipe on.

@verbatim
<code-snippet name="Open a gallery" lang="php">
use Pteal79\ImageLightbox\Facades\ImageLightbox;

$data = [
    ['index' => 0, 'uuid' => 'a1b2', 'file' => 'https://example.com/one.jpg', 'text' => 'Front elevation'],
    ['index' => 1, 'uuid' => 'c3d4', 'file' => '/var/mobile/…/two.jpg',      'text' => 'Rear elevation'],
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
</code-snippet>
@endverbatim

Everything is chainable: `ImageLightbox::gallery($data)->config(['share' => true])->start();`

`ImageLightbox::gallery()` with no arguments returns the same instance for the rest of the request, so the dataset can be refreshed from anywhere:

@verbatim
<code-snippet name="Update an open gallery" lang="php">
ImageLightbox::gallery()->update($freshData);   // swaps the dataset in place
ImageLightbox::gallery()->close();              // dismisses the lightbox
</code-snippet>
@endverbatim

`update()` keeps the reader on the same image where its `uuid` survives; an empty dataset closes the lightbox.

| Method            | Description                                                                     |
|-------------------|---------------------------------------------------------------------------------|
| `gallery($data)`  | Sets the dataset and returns the gallery instance.                              |
| `config($array)`  | Toggles `edit`, `markup`, `share`, `delete`, `title`.                            |
| `open($index)`    | Opens on the row with that `index`, else treats it as a position.                |
| `start()`         | Opens on the first row.                                                          |
| `last()`          | Opens on the last row.                                                           |
| `update($data)`   | Replaces the dataset and pushes it into an open lightbox.                        |
| `setItems($data)` | Replaces the dataset without touching an open lightbox.                          |
| `close()`         | Dismisses an open lightbox.                                                      |

### Gestures

Pinch or double-tap to zoom (up to 5x), pan while zoomed, swipe horizontally between gallery images, and swipe down to dismiss. Swipe-to-dismiss is only active at the default zoom level and fires `ClosePressed` just like the close button.

### Events

Each event carries `imageId` (the `imageId` from `show()`, or the row's `uuid`) and `index`. Both are optional — take either, both, or neither.

Events fire after the lightbox dismisses, except `DeletePressed` in a gallery of more than one image: that one fires while the gallery stays open, so the handler deletes the row and calls `update()`.

@verbatim
<code-snippet name="Listening for ImageLightbox events" lang="php">
use Native\Mobile\Attributes\OnNative;
use Pteal79\ImageLightbox\Events\DeletePressed;
use Pteal79\ImageLightbox\Events\EditPressed;
use Pteal79\ImageLightbox\Facades\ImageLightbox;

#[OnNative(EditPressed::class)]
public function handleEditPressed(?string $imageId = null, ?int $index = null): void
{
    // lightbox already closed — open your editor here
}

#[OnNative(DeletePressed::class)]
public function handleDeletePressed(?string $imageId = null, ?int $index = null): void
{
    Photo::where('uuid', $imageId)->delete();

    // gallery is still open — refresh it in place
    ImageLightbox::gallery()->update($this->photoRows());
}
</code-snippet>
@endverbatim

Available events: `EditPressed`, `MarkupPressed`, `DeletePressed`, `ClosePressed` (all under `Pteal79\ImageLightbox\Events`).

### JavaScript Usage (Vue / React / Inertia)

@verbatim
<code-snippet name="ImageLightbox in JavaScript" lang="javascript">
import { ImageLightbox, Events } from '@pteal79/plugin-image-lightbox';
import { on, off } from '@nativephp/native';

await ImageLightbox.show({ url: 'https://example.com/photo.jpg', imageId: 'abc-123', share: true });

const gallery = ImageLightbox.gallery(rows).config({ share: true, delete: true });
await gallery.start();
await gallery.update(rows);
await gallery.close();

const handler = ({ imageId, index }) => console.log('Edit pressed for', imageId, index);
on(Events.EditPressed, handler);
// later…
off(Events.EditPressed, handler);
</code-snippet>
@endverbatim
