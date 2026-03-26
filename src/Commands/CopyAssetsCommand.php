<?php

namespace Nativephp\ImageLightbox\Commands;

use Native\Mobile\Plugins\Commands\NativePluginHookCommand;

class CopyAssetsCommand extends NativePluginHookCommand
{
    protected $signature = 'nativephp:image-lightbox:copy-assets';

    protected $description = 'Copy assets for ImageLightbox plugin';

    public function handle(): int
    {
        if ($this->isAndroid()) {
            $this->copyAndroidAssets();
        }

        if ($this->isIos()) {
            $this->copyIosAssets();
        }

        return self::SUCCESS;
    }

    protected function copyAndroidAssets(): void
    {
        $this->info('Android assets copied for ImageLightbox');
    }

    protected function copyIosAssets(): void
    {
        $this->info('iOS assets copied for ImageLightbox');
    }
}
