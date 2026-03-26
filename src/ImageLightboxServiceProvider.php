<?php

namespace Pteal79\ImageLightbox;

use Illuminate\Support\ServiceProvider;
use Pteal79\ImageLightbox\Commands\CopyAssetsCommand;

class ImageLightboxServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(ImageLightbox::class, function () {
            return new ImageLightbox();
        });
    }

    public function boot(): void
    {
        if ($this->app->runningInConsole()) {
            $this->commands([
                CopyAssetsCommand::class,
            ]);
        }
    }
}
