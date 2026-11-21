#!/usr/bin/env python3
"""Build the deck-editor theme's images, sounds and movies from your own copies of the game and its HD texture pack.
None of the output is part of this repository (git ignores it): it is Konami's work, and the pack author's.

  ./make_assets.py <Tag Force 1 ISO> <HD pack>/ULUS10136

  ISO       Yu-Gi-Oh! GX Tag Force (PSP). Tested with the European release.
  HD pack   TFHD-2022 v1.0 HQ by chrisfand, a PPSSPP texture pack: https://forums.ppsspp.org/showthread.php?tid=28535
            (four times the PSP's textures, so the 1920x1080 layout draws them 1:1)

Needs Pillow, 7z and ffmpeg. Writes about 21 MB next to this file.
"""
import os, re, struct, subprocess, sys, tempfile
from PIL import Image

OUT = os.path.dirname(os.path.abspath(__file__))
save = lambda im, name: im.save(os.path.join(OUT, name + '.png'))
# Each tag partner's summon movie is their own ace monster's: Flame Wingman, a Vehicroid, Master of Oz, Cyber Blader,
# Armed Dragon, Water Dragon, Cyber End Dragon.
MOVIES = {'jaden': 'fwm_summon', 'syrus': 'jairoid_summon', 'chumley': 'oz_summon', 'alexis': 'cyb_summon_a',
          'chazz': 'armed_summon', 'bastion': 'water_summon', 'zane': 'ced_summon'}
SOUND_EFFECTS = 59  # the sound bank ends with this many effects, after the music and about 15,000 voice clips


def from_pack(pack):
    load = lambda rel: Image.open(os.path.join(pack, rel)).convert('RGBA')

    # Card frames: the pack stores each as a top piece, the two side borders lying on their sides, and a bottom piece.
    for kind in ['normal', 'effect', 'ritual', 'spell', 'trap']:
        src = load(f'cards/frame/{kind}.png')
        card = Image.new('RGBA', (432, 616))
        card.paste(src.crop((0, 0, 432, 136)), (0, 0))
        card.paste(src.crop((0, 136, 312, 196)).rotate(90, expand=True), (0, 136))    # left border
        card.paste(src.crop((0, 196, 312, 256)).rotate(90, expand=True), (372, 136))  # right border
        card.paste(src.crop((0, 256, 432, 424)), (0, 448))
        assert card.getpixel((216, 300))[3] == 0, 'art window should stay open'
        save(card, f'frame-{kind}')

    icons = load('cards/frame/icons_attribute_race.png')
    for name, x, y in [('light', 1, 272), ('fire', 205, 272), ('wind', 341, 272), ('spell', 409, 272), ('trap', 341, 342)]:
        save(icons.crop((x, y, x + 62, y + 64)), f'attr-{name}')
    save(icons.crop((223, 476, 254, 510)), 'star')

    labels = load('cards/frame/icons_spell_trap.png')
    for name, y in [('trap', 9), ('spell', 73)]:
        row = labels.crop((200, y, 512, y + 25))
        save(row.crop(row.getbbox()), f'label-{name}')

    save(load('cards/frame/cardsmall.png'), 'back')
    save(load('ui/deckedit/00000000f1fc5e4cf83d4357.png').crop((0, 0, 242, 58)), 'tab-deck')
    save(load('ui/deckedit/000000006c2037fcdf435532.png').crop((0, 0, 242, 61)), 'tab-side')
    save(load('ui/deckedit/00000000894a59a6c4fc9f5c.png').crop((0, 0, 548, 96)), 'bar-dark')
    save(load('ui/deckedit/000000003416c1579e1b269c.png').crop((0, 0, 604, 96)), 'bar-count')
    # Duel Academy for the background, one per time of day. The pack keeps the 480x272 screen in a 2048x1024 texture.
    for phase in ['day', 'dusk', 'night']:
        load(f'backgrounds/academy_{phase}.png').crop((0, 0, 1920, 1024)).convert('RGB').resize((1920, 1080), Image.BICUBIC) \
            .save(os.path.join(OUT, f'academy-{phase}.jpg'), quality=88)
    # Tag partners: calm face (a cell of the pre-duel sheet) and shouting face (duel portrait, whose face sits in the
    # texture's top-left corner), plus the game's speech bubble.
    calm = load('npcs/portraits/pre_duel/00000000910e2646160966ae.png')
    for name, col, row, duel in [('jaden', 1, 0, 'c7d002ed'), ('syrus', 2, 0, '86eef9c7'), ('chumley', 3, 0, '95dd442d'),
                                 ('alexis', 4, 0, '6ae6287a'), ('chazz', 5, 0, '1d7f0b4c'), ('bastion', 7, 0, '1cc933b4'),
                                 ('zane', 0, 1, '262233eb')]:
        save(calm.crop((col * 256, row * 256, col * 256 + 256, row * 256 + 256)), f'partner-{name}')
        save(load(f'npcs/portraits/duel/0000000040201f35{duel}.png').crop((0, 0, 162, 162)), f'partner-{name}-shout')
    save(load('ui/text_boxes.png').crop((0, 0, 126, 92)), 'bubble')
    # Summon: a monster zone, its highlight, the lens flare and the pre-duel burst (black where empty, drawn additively).
    save(load('ui/duels/fieldzones/zone_monster.png'), 'zone')
    save(load('ui/duels/fieldzones/highlight1.png'), 'zone-on')
    save(load('ui/duels/fieldzones/lens.png'), 'flare')
    load('ui/misc/preduel_background.png').convert('RGB').save(os.path.join(OUT, 'burst.jpg'), quality=88)


def ehp_file(d, name):
    """One file out of a Tag Force .ehp archive: a table of (name offset, data offset); the size follows the name."""
    assert d[:3] == b'EHP', 'not an .ehp archive'
    for i in range(struct.unpack_from('<I', d, 12)[0]):
        name_at, data_at = struct.unpack_from('<II', d, 16 + i * 8)
        end = d.index(b'\0', name_at)
        if d[name_at:end].decode('ascii', 'replace') == name:
            return d[data_at:data_at + struct.unpack_from('<I', d, end + 1)[0]]
    raise KeyError(name)


def gim(d):
    """A PSP .gim texture (palette-indexed with a 32-bit palette, as the game's UI uses) as an image."""
    assert d[:3] == b'MIG', 'not a .gim texture'
    at = {}
    def walk(pos, end):
        while pos < end:
            kind, _, size, nxt, data = struct.unpack_from('<HHIII', d, pos)
            if kind in (2, 3): walk(pos + data, pos + size)   # file and picture blocks hold the others
            elif kind in (4, 5): at.setdefault(kind, pos + 16)  # 4 image, 5 palette
            if not size: break
            pos += nxt if kind in (4, 5) else size
    walk(16, len(d))
    def pixels(p):
        fmt, order, w, h, bpp, pitch, rows = struct.unpack_from('<7H', d, p + 4)
        aw, ah = -(-w // pitch) * pitch, -(-h // rows) * rows
        row = aw * bpp // 8
        if order == 1: row = -(-row // 16) * 16; aw = row * 8 // bpp  # swizzled rows are whole 16-byte blocks
        start = struct.unpack_from('<I', d, p + 0x1c)[0]
        raw = d[p + start:p + start + row * ah]
        if order == 1:  # swizzled: stored as 16-byte x 8-row tiles
            flat = bytearray(len(raw)); src = 0
            for by in range(0, ah, 8):
                for bx in range(0, row, 16):
                    for y in range(8):
                        flat[(by + y) * row + bx:(by + y) * row + bx + 16] = raw[src:src + 16]; src += 16
            raw = bytes(flat)
        return fmt, w, h, aw, raw
    fmt, w, h, aw, raw = pixels(at[4])
    pal_fmt, *_, colors = pixels(at[5])
    assert fmt in (4, 5) and pal_fmt == 3, 'only 4/8-bit indexed textures with an RGBA8888 palette are handled'
    idx = bytes(b for byte in raw for b in (byte & 15, byte >> 4)) if fmt == 4 else raw
    colors += bytes(4 * 256)
    rgba = b''.join(colors[i * 4:i * 4 + 4] for i in idx[:aw * h])
    return Image.frombytes('RGBA', (aw, h), rgba).crop((0, 0, w, h))


def ffmpeg(src, *args):
    subprocess.run(['ffmpeg', '-v', 'fatal', '-y', '-i', src, *args], check=True)


def from_iso(iso):
    usr = 'PSP_GAME/USRDIR/'
    want = ['deck/all_e.ehp', 'snd/psp_snddat.bin'] + [f'movie/{m}.pmf' for m in MOVIES.values()]
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(['7z', 'x', '-y', f'-o{tmp}', iso] + [usr + f for f in want], check=True, stdout=subprocess.DEVNULL)
        path = lambda rel: os.path.join(tmp, usr, rel)

        # The deck editor's own background tile (the pack doesn't replace it), enlarged to the pack's scale.
        tile = gim(ehp_file(open(path('deck/all_e.ehp'), 'rb').read(), 'bg0001.gim')).convert('RGB')
        save(tile.resize((tile.width * 4, tile.height * 4), Image.BICUBIC), 'bg')

        # Sound effects: every clip in the bank is an ATRAC3 RIFF; the effects are the last ones. se/00.ogg ... se/58.ogg
        bank = open(path('snd/psp_snddat.bin'), 'rb').read()
        riffs = [m.start() for m in re.finditer(b'RIFF', bank) if bank[m.start() + 8:m.start() + 12] == b'WAVE'][-SOUND_EFFECTS:]
        os.makedirs(os.path.join(OUT, 'se'), exist_ok=True)
        for n, start in enumerate(riffs):
            clip = os.path.join(tmp, 'clip.at3')
            open(clip, 'wb').write(bank[start:start + struct.unpack_from('<I', bank, start + 4)[0] + 8])
            ffmpeg(clip, '-af', 'silenceremove=start_periods=1:start_threshold=-55dB', '-c:a', 'libvorbis', '-q:a', '4',
                   os.path.join(OUT, 'se', f'{n:02d}.ogg'))

        # Summon movies: silent 480x272 in the game, doubled here so the TV has less scaling to do.
        for partner, movie in MOVIES.items():
            ffmpeg(path(f'movie/{movie}.pmf'), '-vf', 'scale=960:544:flags=lanczos', '-c:v', 'libx264', '-profile:v', 'high',
                   '-crf', '25', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', '-an', os.path.join(OUT, f'summon-{partner}.mp4'))


if __name__ == '__main__':
    if len(sys.argv) != 3 or not os.path.isfile(sys.argv[1]) or not os.path.isdir(os.path.join(sys.argv[2], 'cards', 'frame')):
        sys.exit(__doc__)
    from_pack(sys.argv[2])
    from_iso(sys.argv[1])
    print(f'done: {len(os.listdir(OUT)) - 1} files in {OUT}')
