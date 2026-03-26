## nativephp/plugin-image-lightbox

Displays images (jpg, jpeg, png, heic) in a full-screen native lightbox overlay with pinch-to-zoom, pan, optional Edit/Markup/Share buttons, and native share-sheet support.

### Installation

```bash
composer require nativephp/plugin-image-lightbox
php artisan vendor:publish --tag=nativephp-plugins-provider
php artisan native:plugin:register nativephp/plugin-image-lightbox
php artisan native:plugin:list
```

### PHP Usage (Livewire/Blade)

Use the `ImageLightbox` facade:

@verbatim
<code-snippet name="Show a lightbox" lang="php">
use Nativephp\ImageLightbox\Facades\ImageLightbox;

ImageLightbox::show([
    'image'   => 'https://example.com/photo.heic',
    'imageId' => '550e8400-e29b-41d4-a716-446655440000',
    'edit'    => true,
    'markup'  => true,
    'share'   => true,
]);
</code-snippet>
@endverbatim

### Available Options

| Parameter | Type     | Default | Description                              |
|-----------|----------|---------|------------------------------------------|
| `image`   | string   | —       | **Required.** Local path or remote URL.  |
| `imageId` | string   | `null`  | Identifier forwarded in event payloads.  |
| `edit`    | bool     | `false` | Show an Edit button.                     |
| `markup`  | bool     | `false` | Show a Markup button.                    |
| `share`   | bool     | `false` | Show a Share button.                     |

### Events

@verbatim
<code-snippet name="Listening for ImageLightbox events" lang="php">
use Native\Mobile\Attributes\OnNative;
use Nativephp\ImageLightbox\Events\EditPressed;
use Nativephp\ImageLightbox\Events\MarkupPressed;

#[OnNative(EditPressed::class)]
public function handleEditPressed(?string $imageId = null): void
{
    // lightbox already closed — open your editor here
}

#[OnNative(MarkupPressed::class)]
public function handleMarkupPressed(?string $imageId = null): void
{
    // lightbox already closed — open your markup tool here
}
</code-snippet>
@endverbatim

### JavaScript Usage (Vue / React / Inertia)

@verbatim
<code-snippet name="ImageLightbox in JavaScript" lang="javascript">
import { ImageLightbox, Events } from '@nativephp/plugin-image-lightbox';
import { on, off } from '@nativephp/native';

await ImageLightbox.show({
    image:   'https://example.com/photo.jpg',
    imageId: 'abc-123',
    share:   true,
});

const handler = ({ imageId }) => console.log('Edit pressed for', imageId);
on(Events.EditPressed, handler);
// later…
off(Events.EditPressed, handler);
</code-snippet>
@endverbatim
