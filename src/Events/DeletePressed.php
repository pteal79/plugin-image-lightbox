<?php

namespace Pteal79\ImageLightbox\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class DeletePressed
{
    use Dispatchable, SerializesModels;

    /**
     * @param  string|null  $imageId  The `imageId` (single image) or row `uuid` (gallery).
     * @param  int|null     $index    The gallery row's `index`; `0` for a single image.
     */
    public function __construct(
        public readonly ?string $imageId = null,
        public readonly ?int $index = null,
    ) {}
}
