<?php

namespace Pteal79\ImageLightbox;

class ImageLightbox
{
    /**
     * Display an image in a full-screen native lightbox overlay.
     *
     * Provide either `url` (remote http/https/php://) or `local` (absolute local file path),
     * but not both. At least one is required.
     *
     * NativePHP serves the embedded PHP app over a local `php://host:port` scheme.
     * These URLs are rewritten to `http://` before being forwarded to the native image loader.
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

        // NativePHP exposes the embedded PHP server via a php:// scheme.
        // Native HTTP clients (HttpURLConnection / URLSession) expect http://, so rewrite.
        if ($url !== null && str_starts_with($url, 'php://')) {
            $url = 'http://' . substr($url, strlen('php://'));
        }

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
}
