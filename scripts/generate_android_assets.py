import os
from PIL import Image

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICON_PATH = os.path.join(BASE_DIR, "sepsales_icon.png")
SPLASH_PATH = os.path.join(BASE_DIR, "SEP Sales App Splash.png")
RES_DIR = os.path.join(BASE_DIR, "android", "app", "src", "main", "res")

ICON_SIZES = {
    "mipmap-mdpi": (48, 48),
    "mipmap-hdpi": (72, 72),
    "mipmap-xhdpi": (96, 96),
    "mipmap-xxhdpi": (144, 144),
    "mipmap-xxxhdpi": (192, 192)
}

SPLASH_SIZES = {
    "drawable": (1080, 1920),
    "drawable-port": (1080, 1920),
    "drawable-land": (1920, 1080),
    "drawable-port-mdpi": (320, 480),
    "drawable-port-hdpi": (480, 800),
    "drawable-port-xhdpi": (720, 1280),
    "drawable-port-xxhdpi": (960, 1600),
    "drawable-port-xxxhdpi": (1280, 1920),
    "drawable-land-mdpi": (480, 320),
    "drawable-land-hdpi": (800, 480),
    "drawable-land-xhdpi": (1280, 720),
    "drawable-land-xxhdpi": (1600, 960),
    "drawable-land-xxxhdpi": (1920, 1280),
}

def generate_icons():
    print(f"Loading icon from {ICON_PATH}...")
    icon_img = Image.open(ICON_PATH)
    
    for folder, (w, h) in ICON_SIZES.items():
        folder_path = os.path.join(RES_DIR, folder)
        os.makedirs(folder_path, exist_ok=True)
        
        resized = icon_img.resize((w, h), Image.Resampling.LANCZOS)
        
        # Save ic_launcher.png and ic_launcher_round.png
        resized.save(os.path.join(folder_path, "ic_launcher.png"), "PNG")
        resized.save(os.path.join(folder_path, "ic_launcher_round.png"), "PNG")
        print(f"Generated {folder}/ic_launcher.png ({w}x{h})")

    # Also save fallback in drawable/ic_launcher.png
    drawable_dir = os.path.join(RES_DIR, "drawable")
    os.makedirs(drawable_dir, exist_ok=True)
    icon_img.resize((512, 512), Image.Resampling.LANCZOS).save(os.path.join(drawable_dir, "ic_launcher.png"), "PNG")
    icon_img.resize((512, 512), Image.Resampling.LANCZOS).save(os.path.join(drawable_dir, "ic_launcher_foreground.png"), "PNG")

def generate_splash():
    print(f"Loading splash from {SPLASH_PATH}...")
    splash_img = Image.open(SPLASH_PATH)
    
    for folder, (w, h) in SPLASH_SIZES.items():
        folder_path = os.path.join(RES_DIR, folder)
        os.makedirs(folder_path, exist_ok=True)
        
        resized = splash_img.resize((w, h), Image.Resampling.LANCZOS)
        resized.save(os.path.join(folder_path, "splash.png"), "PNG")
        print(f"Generated {folder}/splash.png ({w}x{h})")

if __name__ == "__main__":
    generate_icons()
    generate_splash()
    print("All Android icon and splash screen assets generated successfully!")
