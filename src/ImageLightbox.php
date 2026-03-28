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
     * `php://` stream URLs (e.g. `php://memory`, `php://input`) are resolved on the PHP side:
     * their contents are written to a temporary file and passed as a local path to native.
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

        // php:// stream wrappers cannot be fetched by native HTTP clients;
        // resolve them here and hand the result off as a local temp file.
        if ($url !== null && str_starts_with($url, 'php://')) {
            $data = file_get_contents($url);
            if ($data !== false) {
                $tmp = tempnam(sys_get_temp_dir(), 'nplb_');
                file_put_contents($tmp, $data);
                $local = $tmp;
            }
            $url = null;
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
