"""Generate our mobile layouts from independently entered conventional letter rows.

See docs/layout-sources.md. No external layout files are read or downloaded.
"""
import json
from pathlib import Path

LAYOUTS = {
    'ar': ('ar-SA', ['ضصثقفغعهخحجد', 'شسيبلاتنمكط', 'ئ ء ؤ ر لا ى ة و ز ظ'], {'ض': 'ذ', 'ا': 'أإآ', 'و': 'ؤ', 'ي': 'ئ'}, True),
    'he': ('he-IL', ["/'קראטוןםפ", 'שדגכעיחלךף', 'זסבהנמצתץ.'], {}, True),
    'el': ('el-GR', [';ςερτυθιοπ', 'ασδφγηξκλ', 'ζχψωβνμ'], {'α': 'ά', 'ε': 'έ', 'η': 'ή', 'ι': 'ίϊΐ', 'ο': 'ό', 'υ': 'ύϋΰ', 'ω': 'ώ'}, False),
    'tr': ('tr-TR', ['qwertyuıopğü', 'asdfghjklşi', 'zxcvbnmöç'], {}, False),
    'it': ('it-IT', ['qwertyuiopè+', 'asdfghjklòàù', 'zxcvbnm,.-'], {'e': 'é', 'i': 'ì', 'o': 'ó'}, False),
    'pt': ('pt-PT', ['qwertyuiop', 'asdfghjklç', 'zxcvbnm,.-'], {'a': 'áàâã', 'e': 'éê', 'i': 'í', 'o': 'óôõ', 'u': 'ú', 'c': 'ç'}, False),
    'cs': ('cs-CZ', ['qwertzuiopú', 'asdfghjklů', 'yxcvbnm,.-'], {'e': 'ěé', 's': 'š', 'c': 'č', 'r': 'ř', 'z': 'ž', 'y': 'ý', 'a': 'á', 'i': 'í', 'd': 'ď', 't': 'ť', 'n': 'ň'}, False),
    'hu': ('hu-HU', ['qwertzuiopőú', 'asdfghjkléáű', 'íyxcvbnm,.-'], {'o': 'öó', 'u': 'ü', 'i': 'í'}, False),
    'sv': ('sv-SE', ['qwertyuiopå', 'asdfghjklöä', 'zxcvbnm,.-'], {'e': 'é', 'u': 'ü'}, False),
    'fi': ('fi-FI', ['qwertyuiopå', 'asdfghjklöä', 'zxcvbnm,.-'], {'s': 'š', 'z': 'ž', 'e': 'é'}, False),
    'nb': ('nb-NO', ['qwertyuiopå', 'asdfghjkløæ', 'zxcvbnm,.-'], {'e': 'éè', 'a': 'à', 'o': 'óô'}, False),
    'da': ('da-DK', ['qwertyuiopå', 'asdfghjklæø', 'zxcvbnm,.-'], {'e': 'é'}, False),
}

def key(label, **props):
    return {'key': label, **props}

def serialize(value, depth=0):
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, dict):
        return '(' + ', '.join(json.dumps(k) + ': ' + serialize(v, depth + 1) for k, v in value.items()) + ')'
    return '(\n' + ',\n'.join('    ' * (depth + 1) + serialize(v, depth + 1) for v in value) + '\n' + '    ' * depth + ')'

def generate(name, language, lines, accents, rtl):
    rows = []
    for index, line in enumerate(lines):
        row = []
        if index == 2:
            row.append(key('⇧', mode='meta', meta='65', code='59', width='1.3'))
        for pos, letter in enumerate(line.split() if ' ' in line else line):
            entry = key(letter)
            if len(letter) > 1:
                entry['text'] = letter
            alternatives = accents.get(letter, '')
            if alternatives:
                # A string action is a layout-native hold action; variants also have swipe positions.
                entry['hold'] = alternatives[0]
            row.append(entry if not alternatives else [entry, *alternatives])
        if index == 2:
            row.append(key('⌫', code='-67', holdRepeat='1', width='1.3', top='@DELETE_WORD', bottom='@DELETE_WORD_FORWARD'))
        rows.append(row)
    rows.append([
        key('123', layout='@NUM'), key(name.upper(), layout='@NEXT', hold='layout:@EMOJI'),
        key('،' if rtl and name == 'ar' else ',', hold='@VOICE'),
        key(' ', code='-62', width='4', mode='joy', left='-21', right='-22', top='-19', bottom='-20'),
        key('.', hold='؟' if name == 'ar' else '?'), key('⏎', code='-66', width='1.5'),
    ])
    metadata = {'speechLang': language, 'language': language, 'rtl': '1' if rtl else '0'}
    # Convert keys with popups to Flexaml's params + positional children form.
    def key_text(item):
        if isinstance(item, dict):
            return serialize(item)
        base, *popups = item
        return serialize(base)[:-1] + ', ' + ', '.join(serialize(p) for p in popups) + ')'
    body = ',\n'.join('    (\n' + ',\n'.join('        ' + key_text(k) for k in row) + '\n    )' for row in rows)
    return '# Independently authored mobile letter layout; see docs/layout-sources.md.\n(\n    ' + ', '.join(serialize(k) + ': ' + serialize(v) for k, v in metadata.items()) + ',\n' + body + '\n)\n'

if __name__ == '__main__':
    target = Path(__file__).resolve().parents[1] / 'app/src/main/assets'
    for name, definition in LAYOUTS.items():
        (target / f'{name}.txt').write_text(generate(name, *definition), encoding='utf-8', newline='\n')
    print(f'Generated {len(LAYOUTS)} independently authored layouts')
