<?php

namespace Pteal79\ImageLightbox;

class ImageLightbox
{
    /** The gallery instance shared for the lifetime of the request. */
    protected ?Gallery $gallery = null;

    /**
     * Display an image in a full-screen native lightbox overlay.
     *
     * Provide either `url` (remote http/https/php://) or `local` (absolute local file path),
     * but not both. At least one is required.
     *
     * `php://` URLs (NativePHP's local server scheme) are passed through to native as-is.
     *
     * @param array{
     *     url?: string|null,
     *     local?: string|null,
     *     imageId?: string|null,
     *     edit?: bool,
     *     markup?: bool,
     *     share?: bool,
     *     delete?: bool
     * } $options
     */
    public function show(array $options): void
    {
        $hasUrl   = ! empty($options['url']);
        $hasLocal = ! empty($options['local']);

        if (! $hasUrl && ! $hasLocal) {
            return;
        }

        $url   = $hasUrl   ? (string) $options['url']   : null;
        $local = $hasLocal ? (string) $options['local'] : null;

        $payload = [
            'url'      => $url,
            'local'    => $local,
            'imageId'  => $options['imageId'] ?? null,
            'edit'     => (bool) ($options['edit']   ?? false),
            'markup'   => (bool) ($options['markup'] ?? false),
            'share'    => (bool) ($options['share']  ?? false),
            'delete'   => (bool) ($options['delete'] ?? false),
        ];

        if (function_exists('nativephp_call')) {
            nativephp_call('ImageLightbox.Show', json_encode($payload));
        }
    }

    /**
     * Get the gallery instance, optionally seeding it with a dataset.
     *
     * Each row of the dataset is shaped `['index' => …, 'uuid' => …, 'file' => …, 'text' => …]`.
     * Passing no arguments returns the existing instance untouched, so a dataset
     * set earlier in the request can be reused:
     *
     *     $gallery = ImageLightbox::gallery($data);
     *     $gallery->config(['share' => true])->start();
     *     // later …
     *     ImageLightbox::gallery()->update($data);
     *
     * @param  iterable<array-key, mixed>|null  $items
     */
    public function gallery(?iterable $items = null): Gallery
    {
        if ($this->gallery === null) {
            return $this->gallery = new Gallery($items ?? []);
        }

        if ($items !== null) {
            $this->gallery->setItems($items);
        }

        return $this->gallery;
    }
}
