#!/usr/bin/env python3
"""Build the Project DIVA song-select theme's images, sounds, songs and movie from your own copy of the game.
None of the output is part of this repository (git ignores it): it is SEGA's and Crypton's work.

  ./make_assets.py [game folder] [--songs all | --songs "Po Pi Po,Melt,..."]

  game folder   Hatsune Miku: Project DIVA Mega Mix+ (Steam). Default: the usual Steam library path.
  --songs       which songs the menu can play. Default: a short list (see SONGS). "all" is every song you own
                (about 260, 3 GB with their 19 movies, 15 minutes).

Needs Pillow, numpy, cryptography and ffmpeg. Writes next to this file.
"""
import io, json, os, re, struct, subprocess, sys, tempfile, wave, zlib
import numpy as np
from PIL import Image
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

OUT = os.path.dirname(os.path.abspath(__file__))
GAME = os.path.expanduser('~/.local/share/Steam/steamapps/common/Hatsune Miku Project DIVA Mega Mix Plus')
SONGS = ['Po Pi Po', 'The World is Mine', 'Melt', 'Senbonzakura', 'Tell Your World', 'Rolling Girl', 'Love is War',
         'Ievan Polkka', 'Miracle Paint', 'Romeo and Cinderella', 'Electric Angel', 'Double Lariat', 'Just Be Friends',
         'Ghost Rule', 'Alien Alien', 'Weekender Girl', 'Hand in Hand', '39',
         # Miku's songs whose music video is a movie file (about 830 MB of movies):
         'Greenlights Serenade', 'Hibikase', 'Dramaturgy', 'slump', 'Song of Eternity -DIVAMIX-', 'Suna no Wakusei feat. Hatsune Miku',
         'Bless Your Breath', 'Pink Moon']
# The game's own song-select music, offered next to the songs.
LOOPS = {'selector_ft_lp': 'Song Select (Future Tone)', 'selector_verB_a_lp': 'Song Select (Arcade)', 'selector_vox_lp': 'Song Select (Mega Mix)'}
SOUND_BANKS = ['se_sel', 'se_cmn', 'button']
# The game's own 2D animations the page plays back, by layout set and root layer (None: all of them). Their sprites
# are saved as the animations name them; everything is authored for a 1920x1080 screen at 60 frames a second.
ANIMS = {
    'nswgam_songselector': ['song_bg', 'header_belt', 'list_in', 'list_loop', 'list_up', 'list_down', 'list', 'list sel', 'list_base_nml_sel',
                            'lv_tab_arcade', 'lv_tab_arcade_esy_off', 'lv_tab_arcade_nml_off', 'lv_tab_arcade_hrd_off',
                            'lv_tab_arcade_ext_off', 'lv_tab_arcade_exex_off', 'nsw_menu_fotter_arcade', 'song_num', 'scrl', 'scrl_base'],
    'gam_cmn': [f'{part}_{shape}' for part in ['target', 'button'] for shape in ['maru', 'batsu', 'sankaku', 'shikaku']] + ['target_eff', 'hit_eff00', 'combo_cool001'],
    'nswgam_cmnbg': None,
    'nswgam_cmn': ['now_loading', 'loading_bg'],
    'nswgam_option': ['submenu_anm_03', 'option_submenu_01__f', 'footer_01', 'help_text', 'footer_back'],
    'nswgam_playlist': ['playlist_song_win_base_in', 'sound_list_01', 'songlist_scrl_bar'],
}
# Sprites the page places itself, into slots those animations leave empty (the bar of each difficulty, level icons, digits).
EXTRA_SPRITES = {'nswgam_songselector': r'LIST_BASE_(ESY|NML|HRD|EXT|EXEX)(_SEL)?|(ESY|NML|HRD|EXT|EXEXT)_LV_ICON_LV.*|SONG_NUM40X34_\d\d',
                 'nswgam_playlist': r'NUM_SONGLIST_\d\d'}
# The game's 36-pixel fonts: sprite set, and its entry in the font table.
FONTS = {'regular': ('fnt_36latin9', 20), 'bold': ('fnt_bold36latin9', 21)}
# Every file in the archives is AES-256-CBC encrypted with one key; .farc archives use a second, Future Tone-era key.
# Both are the constants published in MikuMikuLibrary (Archives/CriMw/CpkArchive.cs, Archives/FarcArchive.cs).
CPK_KEY = bytes.fromhex('CF53BF9C3767AFB035544EB996AA2439265D40897ED01C3A6BA65DD5FD6C19A3')
CPK_IV = bytes.fromhex('C255FD73D830FAEFD5320854A2264414')
FARC_KEY = bytes.fromhex('1372D57B6E9E31EBA239B83C1557C6BB')
# Where the same file exists for several platforms, take the PC one first.
PLATFORMS = ['rom_steam_region_dlc', 'rom_steam_region', 'rom_steam_dlc', 'rom_steam_en', 'rom_steam', 'rom_switch_en',
             'rom_switch', 'rom_ps4_patch', 'rom_ps4_dlc', 'rom_ps4', 'rom']


def aes_cbc(key, iv, data):
    d = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
    return d.update(data[:len(data) // 16 * 16]) + d.finalize()


def utf_table(d):
    """A CRI @UTF table (the archive's header and its table of contents) as a list of rows."""
    if d[:4] != b'@UTF':  # masked with a fixed running key
        out = bytearray(len(d)); m = 0x655f
        for i, b in enumerate(d): out[i] = b ^ (m & 0xff); m = (m * 0x4115) & 0xffffffff
        d = bytes(out)
    assert d[:4] == b'@UTF', 'not a CRI table'
    b = d[8:8 + struct.unpack_from('>I', d, 4)[0]]
    rows_at, str_at, data_at, _, ncol, rowlen, nrow = struct.unpack_from('>HIIIHHI', b, 2)
    text = lambda o: b[str_at + o:b.index(b'\0', str_at + o)].decode('utf-8', 'replace')
    fmt = {0: 'B', 1: 'b', 2: 'H', 3: 'h', 4: 'I', 5: 'i', 6: 'Q', 7: 'q', 8: 'f', 9: 'd', 10: 'I', 11: 'II'}
    def val(t, p):
        v = struct.unpack_from('>' + fmt[t], b, p)
        return (text(v[0]) if t == 10 else v[0]), struct.calcsize('>' + fmt[t])
    cols = []; p = 0x18
    for _ in range(ncol):
        flags = b[p]; name = text(struct.unpack_from('>I', b, p + 1)[0]); p += 5; const = None
        if flags & 0xf0 == 0x30: const, n = val(flags & 15, p); p += n
        cols.append((name, flags, const))
    rows = []
    for r in range(nrow):
        p = rows_at + r * rowlen; row = {}
        for name, flags, const in cols:
            if flags & 0xf0 == 0x50: row[name], n = val(flags & 15, p); p += n
            else: row[name] = const
        rows.append(row)
    return rows


class Game:
    """The four .cpk archives as one file tree; read('rom/2d/x.farc') returns the decrypted PC version of a file."""
    def __init__(self, folder):
        self.files = {}
        for name in ['diva_main', 'diva_main_region', 'diva_dlc00', 'diva_dlc00_region']:
            path = os.path.join(folder, name + '.cpk')
            if not os.path.isfile(path): continue  # the DLC archives are optional
            f = open(path, 'rb'); head = utf_table(f.read(0x800)[16:])[0]
            f.seek(head['TocOffset']); toc = utf_table(f.read(16 + head['TocSize'])[16:])
            base = min(head['TocOffset'], head['ContentOffset'] or head['TocOffset'])
            for r in toc:
                platform, _, rel = f"{r['DirName']}/{r['FileName']}".partition('/')
                if platform == 'rom': rel = 'rom/' + rel  # the shared files have no platform folder above rom/
                if platform not in PLATFORMS: continue
                if rel not in self.files or PLATFORMS.index(platform) < PLATFORMS.index(self.files[rel][0]):
                    self.files[rel] = (platform, f, base + r['FileOffset'], r['FileSize'])
        assert self.files, f'no .cpk archives in {folder}'
    def read(self, rel):
        _, f, at, size = self.files[rel]; f.seek(at)
        out = aes_cbc(CPK_KEY, CPK_IV, f.read(size))
        return out[:-out[-1]]  # PKCS7 padding


def farc(d):
    """A Project DIVA .farc archive as {name: bytes}."""
    sig = d[:4]; hsize = struct.unpack_from('>I', d, 4)[0] + 8; out = {}
    def name_at(b, p): e = b.index(b'\0', p); return b[p:e].decode(), e + 1
    gunzip = lambda b: zlib.decompressobj(31).decompress(b)
    if sig in (b'FArc', b'FArC'):  # plain, or gzip only
        p = 12
        while p < hsize:
            name, p = name_at(d, p)
            if sig == b'FArc': at, size = struct.unpack_from('>II', d, p); p += 8; out[name] = d[at:at + size]
            else:
                at, csize, usize = struct.unpack_from('>III', d, p); p += 12
                out[name] = gunzip(d[at:at + csize]) if usize else d[at:at + csize]
        return out
    assert sig == b'FARC', 'not a .farc archive'
    flags, = struct.unpack_from('>I', d, 8)
    h = aes_cbc(FARC_KEY, d[0x10:0x20], d[0x20:hsize]) if flags & 4 else d[0x10:hsize]  # an encrypted header follows its IV
    count, = struct.unpack_from('>I', h, 8); p = 16
    for _ in range(count):
        name, p = name_at(h, p)
        at, csize, usize, eflags = struct.unpack_from('>IIII', h, p); p += 16
        raw = d[at:at + csize]
        if eflags & 4: raw = aes_cbc(FARC_KEY, d[at:at + 16], d[at + 16:at + 32 + -(-csize // 16) * 16])[:csize]
        out[name] = gunzip(raw)[:usize] if eflags & 2 and csize != usize else raw[:usize]
    return out


def sprites(d):
    """A sprite set (spr_*.bin) as {name: image}. Textures are block-compressed and stored bottom-up."""
    DXGI = {6: 71, 7: 71, 8: 74, 9: 77, 10: 80, 11: 83, 15: 98}  # texture format -> DDS format, for Pillow to decode
    def mip(p):
        assert d[p:p + 4] == b'TXP\x02'
        w, h, fmt, _, size = struct.unpack_from('<5I', d, p + 4); raw = d[p + 24:p + 24 + size]
        if fmt in DXGI:
            dds = struct.pack('<4s7I44x8I5I', b'DDS ', 124, 0x81007, h, w, size, 0, 1, 32, 4, 0x30315844, 0, 0, 0, 0, 0, 0x1000, 0, 0, 0, 0)
            return Image.open(io.BytesIO(dds + struct.pack('<5I', DXGI[fmt], 3, 0, 1, 0) + raw)), fmt
        return Image.frombytes({2: 'RGBA', 1: 'RGB', 0: 'L', 12: 'L', 13: 'LA'}[fmt], (w, h), raw), fmt
    def texture(p):
        n, = struct.unpack_from('<I', d, p + 4); offs = struct.unpack_from(f'<{n}I', d, p + 12)
        im, fmt = mip(p + offs[0])
        if fmt == 11 and n >= 2:  # brightness and alpha at full size, colour at half size (BT.709)
            ya = np.asarray(im.convert('RGB'), np.float32) / 255
            c = np.asarray(mip(p + offs[1])[0].convert('RGB').resize(im.size, Image.BILINEAR), np.float32) / 255
            y, a, cb, cr = ya[..., 0], ya[..., 1], c[..., 0] * 1.003922 - .503929, c[..., 1] * 1.003922 - .503929
            rgba = np.stack([y + 1.5748 * cr, y - .1873 * cb - .4681 * cr, y + 1.8556 * cb, a], -1)
            im = Image.fromarray((rgba.clip(0, 1) * 255 + .5).astype(np.uint8), 'RGBA')
        return im.convert('RGBA').transpose(Image.FLIP_TOP_BOTTOM)
    _, tex_at, ntex, nspr, spr_at, _, names_at, _ = struct.unpack_from('<8I', d, 0)
    textures = {}; out = {}
    for i in range(nspr):
        t, _, _, _, _, _, x, y, w, h = struct.unpack_from('<Ii8f', d, spr_at + i * 40)
        p, = struct.unpack_from('<I', d, names_at + i * 4); name = d[p:d.index(b'\0', p)].decode()
        if w < 1 or h < 1: continue
        if t not in textures: textures[t] = texture(tex_at + struct.unpack_from('<I', d, tex_at + 12 + t * 4)[0])
        out[name] = textures[t].crop((round(x), round(y), round(x + w), round(y + h)))
    return out


def sprite_set(game, name):
    return sprites(farc(game.read(f'rom/2d/spr_{name}.farc'))[f'spr_{name}.bin'])


IMA_STEP = [7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
            157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411,
            1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493,
            10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767]


def diva_sound(d, wav_path):
    """A .diva sound effect (4-bit IMA ADPCM, one nibble per channel in turn) written as a WAV file."""
    assert d[:4] == b'DIVA', 'not a .diva sound'
    size, rate, nsamp = struct.unpack_from('<III', d, 8); ch = d[0x1c] or 1
    pred = [0] * ch; idx = [0] * ch; out = []; i = 0
    for byte in d[0x40:0x40 + size]:
        for n in (byte & 15, byte >> 4):
            c = i % ch; i += 1; step = IMA_STEP[idx[c]]
            diff = (step >> 3) + (step >> 2 if n & 1 else 0) + (step >> 1 if n & 2 else 0) + (step if n & 4 else 0)
            pred[c] = max(-32768, min(32767, pred[c] - diff if n & 8 else pred[c] + diff))
            idx[c] = max(0, min(88, idx[c] + (-1, -1, -1, -1, 2, 4, 6, 8)[n & 7])); out.append(pred[c])
    out = out[:nsamp * ch]
    with wave.open(wav_path, 'wb') as w:
        w.setnchannels(ch); w.setsampwidth(2); w.setframerate(rate); w.writeframes(struct.pack(f'<{len(out)}h', *out))


def usm_video(d, ivf_path):
    """The VP9 stream of a CRI .usm movie: an IVF file split across the movie's @SFV chunks."""
    p = 0
    with open(ivf_path, 'wb') as out:
        while p + 16 <= len(d):
            size, = struct.unpack_from('>I', d, p + 4); pad, = struct.unpack_from('>H', d, p + 10)
            if d[p:p + 4] == b'@SFV' and d[p + 15] & 3 == 0: out.write(d[p + 8 + d[p + 9]:p + 8 + size - pad])
            p += 8 + size


def aet_scene(d):
    """A 2D layout/animation set (aet_*.bin): After Effects-like comps of layers with keyframed transforms.
    Returns ({comp id: [layer]}, root comp id). A layer: n name, i/o first and last frame, s start offset, v visible,
    p parent's index, c its comp or spr/w/h its sprite, b blend mode, m 'masked by the layer above', mk markers,
    k the eight curves (anchor x y, position x y, rotation, scale x y, opacity), each a number or [frame, value, slope] keys."""
    text = lambda p: d[p:d.index(b'\0', p)].decode('shift-jis', 'replace').strip() if p else ''
    scene, = struct.unpack_from('<I', d, 0)
    ncomp, comps_at, nvid, vids_at = struct.unpack_from('<IIII', d, scene + 32)
    videos = {}
    for i in range(nvid):
        q = vids_at + i * 20; _, w, h, _, nsrc, srcs = struct.unpack_from('<IHHfII', d, q)
        videos[q] = (w, h, [text(struct.unpack_from('<I', d, srcs + j * 8)[0]) for j in range(nsrc)])
    def curve(p):
        n, at = struct.unpack_from('<II', d, p)
        if n == 0: return 0
        if n == 1: return round(struct.unpack_from('<f', d, at)[0], 3)
        t = struct.unpack_from(f'<{n}f', d, at); v = struct.unpack_from(f'<{2 * n}f', d, at + 4 * n)
        return [[t[i], round(v[2 * i], 3), round(v[2 * i + 1], 4)] for i in range(n)]
    comps = {}
    for i in range(ncomp):
        q = comps_at + i * 8; n, at = struct.unpack_from('<II', d, q); layers = []
        for j in range(n):
            name, start, end, offset, speed, flags, _, kind, item, parent, nmark, marks, video, _ = struct.unpack_from('<IffffHBBIIIIII', d, at + j * 48)
            l = {'n': text(name), 'i': start, 'o': end, 's': offset, 'v': flags & 1, 'p': (parent - at) // 48 if at <= parent < at + n * 48 else -1}
            if speed != 1: l['sp'] = speed
            if nmark: l['mk'] = {text(struct.unpack_from('<fI', d, marks + m * 8)[1]): struct.unpack_from('<fI', d, marks + m * 8)[0] for m in range(nmark)}
            if kind == 1 and item in videos: l['w'], l['h'], l['spr'] = videos[item]
            if kind == 3: l['c'] = item
            if video:
                if d[video] != 3: l['b'] = d[video]  # 3 normal, 5 add
                if d[video + 2]: l['m'] = 1
                l['k'] = [curve(video + 4 + c * 8) for c in range(8)]
            layers.append(l)
        comps[q] = layers
    return comps, comps_at + (ncomp - 1) * 8


def export_anims(game, path):
    """anim.json: the chosen root layers of each set with every comp they use, and each sprite they name as a PNG."""
    sheets = {}; out = {}
    def sheet(name):
        if name not in sheets: sheets[name] = sprite_set(game, name)
        return sheets[name]
    def save(aset, short):
        os.makedirs(path('ui', aset), exist_ok=True); sheet(aset)[short].save(path('ui', aset, short + '.png'))
        return f'{aset}/{short}'
    def sprite_file(name):  # 'NSWGAM_SONGSELECTOR_HEADER' is HEADER in the set nswgam_songselector
        for aset in ANIMS:
            short = name[len(aset) + 1:]
            if name.startswith(aset.upper() + '_') and short in sheet(aset): return save(aset, short)
        print(' no sprite for', name)
    for aset, wanted in ANIMS.items():
        comps, root = aet_scene(game.read(f'rom/2d/aet_{aset}.bin'))
        strip = lambda n: n[len(aset) + 1:] if n.startswith(aset + '_') else n
        roots = {strip(l['n']): l for l in comps[root]}; keep = {}
        def walk(c):
            if c in keep: return
            keep[c] = comps[c]
            for l in comps[c]:
                if 'c' in l: walk(l['c'])
        chosen = {name: roots[name] for name in (wanted or roots)}
        for l in chosen.values():
            l['p'] = -1
            if 'c' in l: walk(l['c'])
        for l in [l for layers in keep.values() for l in layers] + list(chosen.values()):
            l['n'] = strip(l['n'])
            if 'spr' in l: l['spr'] = [sprite_file(n) for n in l['spr']]
        out[aset] = {'roots': chosen, 'comps': keep}
    for aset, pattern in EXTRA_SPRITES.items():
        for short in sheet(aset):
            if re.fullmatch(pattern, short): save(aset, short)
    json.dump(out, open(path('anim.json'), 'w'), separators=(',', ':'))
    print('animations done:', {k: len(v['comps']) for k, v in out.items()})


def export_fonts(game, path):
    """font-*.png and font.json: the Latin part of the game's fonts, one 38-pixel cell per character, with each width."""
    table = farc(game.read('rom/fontmap.farc'))['fontmap.bin']; meta = {'cell': 38, 'columns': 32}
    assert table[:4] == b'FMH3', 'not a font map'
    entries = {}
    for at in struct.unpack_from(f"<{struct.unpack_from('<I', table, 8)[0]}I", table, 0x20):
        fid, _, _, cell_w, cell_h = struct.unpack_from('<I4B', table, at); _, count, chars = struct.unpack_from('<III', table, at + 16)
        entries[fid] = (cell_w, cell_h, {struct.unpack_from('<H', table, chars + i * 8)[0]: table[chars + i * 8 + 4:chars + i * 8 + 8] for i in range(count)})
    for name, (sprites, fid) in FONTS.items():
        atlas = next(iter(sprite_set(game, sprites).values())); cell_w, cell_h, chars = entries[fid]
        codes = sorted(c for c in chars if 0x20 <= c < 0x250)
        sheet = Image.new('RGBA', (32 * 38, -(-len(codes) // 32) * 38)); widths = {}
        for i, code in enumerate(codes):
            col, row, left, width = chars[code]
            sheet.paste(atlas.crop((col * cell_w + left, row * cell_h, col * cell_w + left + width, row * cell_h + cell_h)), (i % 32 * 38, i // 32 * 38))
            widths[chr(code)] = [i, width]
        sheet.save(path(f'font-{name}.png')); meta[name] = widths
    json.dump(meta, open(path('font.json'), 'w'), ensure_ascii=False, separators=(',', ':'))
    print('fonts done')


def ffmpeg(src, *args):
    subprocess.run(['ffmpeg', '-v', 'fatal', '-y', '-i', src, *args], check=True)


def properties(text):
    """The game's 'record.field=value' tables as {record: {field: value}}; the module table's lines start 'module.N.'."""
    out = {}
    for line in text.splitlines():
        key, eq, value = line.partition('=')
        parts = key.split('.')[key.startswith('module.'):]
        if eq and len(parts) >= 2: out.setdefault(parts[0], {})['.'.join(parts[1:])] = value
    return out


def build(game, wanted):
    path = lambda *p: os.path.join(OUT, *p)
    for d in ['ui', 'se', 'songs', 'jk', 'movies']: os.makedirs(path(d), exist_ok=True)
    tmp = tempfile.mkdtemp()
    fresh = lambda *p: not os.path.exists(path(*p))  # songs and movies take the time: a second run only adds what is missing

    export_anims(game, path)
    export_fonts(game, path)

    # Sound effects, numbered in bank order; sounds.json keeps each one's name and the volume the game plays it at.
    sounds = []
    for bank in SOUND_BANKS:
        files = farc(game.read(f'rom/sound/{bank}.farc'))
        volume = {v['file_name']: float(v.get('volume', 1)) for v in properties(files['property.txt'].decode()).values() if 'file_name' in v}
        for name in sorted(n for n in files if n.endswith('.diva')):
            diva_sound(files[name], os.path.join(tmp, 'se.wav'))
            ffmpeg(os.path.join(tmp, 'se.wav'), '-c:a', 'libvorbis', '-q:a', '4', path('se', f'{len(sounds):03d}.ogg'))
            sounds.append([name[:-5], volume.get(name, 1)])
    json.dump(sounds, open(path('sounds.json'), 'w'))
    print(len(sounds), 'sounds done')

    # Songs: channels 1-2 are the music, 3-4 the vocals; mixed to stereo. Each comes with its jacket, its background
    # art, and its movie where the game has one (most songs are drawn live in 3D instead, so they have none).
    db = {}
    for rel in ['rom/pv_db.txt', 'rom/mdata_pv_db.txt']:
        if rel in game.files: db.update(properties(game.read(rel).decode('utf-8', 'replace')))
    songs = []
    for pv in sorted(db):
        info = db[pv]; title = info.get('song_name_en') or info.get('song_name'); num = pv[3:]
        if not title or (wanted != 'all' and title.lower() not in wanted): continue
        src = info.get('song_file_name', ''); art = f'rom/2d/spr_sel_pv{num}.farc'
        if src not in game.files or art not in game.files: continue
        if fresh('songs', num + '.ogg'):
            open(os.path.join(tmp, 'song.ogg'), 'wb').write(game.read(src))
            ffmpeg(os.path.join(tmp, 'song.ogg'), '-af', 'pan=stereo|c0=0.8*c0+0.8*c2|c1=0.8*c1+0.8*c3', '-c:a', 'libvorbis', '-q:a', '4', path('songs', num + '.ogg'))
        # Its movie, where the whole music video is one (19 songs; the rest are drawn live in 3D, some over a movie backdrop).
        # 1080p VP9 at 59.94 fps, silent: the song is its sound. Copied into a .webm as it is, not re-encoded.
        movie = info.get('movie_file_name', '').replace('.mp4', '.usm')
        if info.get('movie_pv_type') == 'ONLY' and movie in game.files and fresh('movies', num + '.webm'):
            usm_video(game.read(movie), os.path.join(tmp, 'movie.ivf'))
            ffmpeg(os.path.join(tmp, 'movie.ivf'), '-c', 'copy', path('movies', num + '.webm'))
        for spr, im in sprites(farc(game.read(art))[f'spr_sel_pv{num}.bin']).items():
            if spr.startswith('SONG_JK'): im.convert('RGB').save(path('jk', num + '.jpg'), quality=88)
            if spr.startswith('SONG_BG'): im.convert('RGB').save(path('jk', num + '-bg.jpg'), quality=85)
        songs.append({'id': num, 'title': title, 'by': info.get('songinfo_en.music', ''), 'bpm': info.get('bpm', ''), 'movie': not fresh('movies', num + '.webm')})  # the game's, or your own recording put there
        print(' song', num, title)
    for name, title in LOOPS.items():
        open(path('songs', name + '.ogg'), 'wb').write(game.read(f'rom/sound/bgm/{name}.ogg'))
        songs.append({'id': name, 'title': title, 'by': 'SEGA', 'bpm': ''})
    json.dump(songs, open(path('songs.json'), 'w'), ensure_ascii=False)
    missing = sorted(set(wanted) - {s['title'].lower() for s in songs}) if wanted != 'all' else []
    if missing: print('not found in your copy:', ', '.join(missing))


if __name__ == '__main__':
    args = sys.argv[1:]; wanted = [s.lower() for s in SONGS]
    if '--songs' in args:
        i = args.index('--songs'); value = args[i + 1]; del args[i:i + 2]
        wanted = 'all' if value == 'all' else [s.strip().lower() for s in value.split(',')]
    folder = args[0] if args else GAME
    if not os.path.isdir(folder): sys.exit(__doc__)
    build(Game(folder), wanted)
    print('done:', OUT)
