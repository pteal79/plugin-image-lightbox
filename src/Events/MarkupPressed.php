<?php

namespace Pteal79\ImageLightbox\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

class MarkupPressed
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public readonly ?string $imageId = null,
    ) {}
}
