/**
 * Translation audit script — compares all locale files against en.ts (source of truth).
 * Reports missing keys, extra keys, and keys that appear to still be in English.
 */
const fs = require('fs');
const path = require('path');

const websiteDir = 'c:/dev/git/eddi-website';
const localesDir = path.join(websiteDir, 'src/i18n/locales');

// Compare structure by extracting key patterns from source
function extractKeyPatterns(content) {
  const keys = [];
  const lines = content.split('\n');
  const indentStack = [];
  
  for (const line of lines) {
    const match = line.match(/^(\t+)([a-zA-Z_]\w*)\s*:/);
    if (match) {
      const depth = match[1].length;
      const key = match[2];
      
      while (indentStack.length >= depth) {
        indentStack.pop();
      }
      indentStack.push(key);
      
      const valueStart = line.substring(line.indexOf(':') + 1).trim();
      const isObject = valueStart === '{' || valueStart === '';
      
      if (!isObject) {
        keys.push(indentStack.join('.'));
      }
    }
  }
  return keys;
}

const localeFiles = fs.readdirSync(localesDir).filter(f => f.endsWith('.ts'));
const locales = localeFiles.map(f => f.replace('.ts', ''));

console.log('=== EDDI Website Translation Audit ===\n');

// File size comparison
console.log('--- File Sizes ---');
for (const locale of locales) {
  const filePath = path.join(localesDir, `${locale}.ts`);
  const stats = fs.statSync(filePath);
  const content = fs.readFileSync(filePath, 'utf-8');
  const lineCount = content.split('\n').length;
  console.log(`  ${locale}: ${stats.size.toLocaleString()} bytes, ${lineCount} lines`);
}

// Extract keys from English (source of truth)
const enContent = fs.readFileSync(path.join(localesDir, 'en.ts'), 'utf-8');
const enKeys = extractKeyPatterns(enContent);
console.log(`\n--- English (source of truth): ${enKeys.length} key patterns ---\n`);

// Compare each locale
console.log('--- Key Pattern Comparison ---');
for (const locale of locales) {
  if (locale === 'en') continue;
  
  const content = fs.readFileSync(path.join(localesDir, `${locale}.ts`), 'utf-8');
  const keys = extractKeyPatterns(content);
  
  const enSet = new Set(enKeys);
  const localeSet = new Set(keys);
  
  const missing = enKeys.filter(k => !localeSet.has(k));
  const extra = keys.filter(k => !enSet.has(k));
  
  if (missing.length === 0 && extra.length === 0) {
    console.log(`  OK ${locale}: All ${keys.length} keys match English`);
  } else {
    console.log(`  !! ${locale}: ${keys.length} keys (${missing.length} missing, ${extra.length} extra)`);
    if (missing.length > 0) {
      console.log(`     Missing: ${missing.join(', ')}`);
    }
    if (extra.length > 0) {
      console.log(`     Extra: ${extra.join(', ')}`);
    }
  }
}

// Check for untranslated strings
console.log('\n--- Checking for Untranslated Strings ---');

function extractStringValues(content) {
  const values = {};
  const lines = content.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    // Match key: 'value' patterns (simple strings only)
    const m1 = line.match(/^\t+([a-zA-Z_]\w*)\s*:\s*'([^']{10,})'/);
    const m2 = line.match(/^\t+([a-zA-Z_]\w*)\s*:\s*"([^"]{10,})"/);
    const match = m1 || m2;
    if (match) {
      const key = match[1];
      const value = match[2];
      if (key && value && !value.includes('${') && !value.includes('http') && !value.includes('@') && !value.startsWith('<') && !value.includes('LABS.AI') && !value.includes('ATU')) {
        values[key] = value;
      }
    }
  }
  return values;
}

const enValues = extractStringValues(enContent);

for (const locale of locales) {
  if (locale === 'en') continue;
  
  const content = fs.readFileSync(path.join(localesDir, `${locale}.ts`), 'utf-8');
  const localeValues = extractStringValues(content);
  
  const untranslated = [];
  for (const [key, value] of Object.entries(localeValues)) {
    if (enValues[key] && value === enValues[key]) {
      untranslated.push(key);
    }
  }
  
  if (untranslated.length === 0) {
    console.log(`  OK ${locale}: No untranslated strings detected`);
  } else {
    console.log(`  !! ${locale}: ${untranslated.length} potentially untranslated strings`);
    console.log(`     Keys: ${untranslated.join(', ')}`);
  }
}

// Check components for hardcoded strings
console.log('\n--- Checking Components for Hardcoded English Text ---');

function findHardcodedStrings(dir, results) {
  try {
    const entries = fs.readdirSync(dir, { withFileTypes: true });
    for (const entry of entries) {
      const fullPath = path.join(dir, entry.name);
      if (entry.isDirectory() && !entry.name.startsWith('.') && entry.name !== 'node_modules') {
        findHardcodedStrings(fullPath, results);
      } else if (entry.name.endsWith('.astro')) {
        if (dir.includes('i18n')) continue;
        const content = fs.readFileSync(fullPath, 'utf-8');
        const lines = content.split('\n');
        
        for (let i = 0; i < lines.length; i++) {
          const line = lines[i].trim();
          if (line.startsWith('import ') || line.startsWith('//') || line.startsWith('*') || line.startsWith('---')) continue;
          
          // Check for text content between HTML tags that isn't using translation
          const htmlTextMatch = line.match(/>([A-Z][a-z]+(?: [a-zA-Z]+){2,})</);
          if (htmlTextMatch && !line.includes('{t.') && !line.includes('set:html') && !line.includes('set:text')) {
            results.push({
              file: path.relative(websiteDir, fullPath),
              line: i + 1,
              text: htmlTextMatch[1].substring(0, 60),
            });
          }
        }
      }
    }
  } catch (e) {}
  return results;
}

const hardcoded = findHardcodedStrings(path.join(websiteDir, 'src'), []);

if (hardcoded.length === 0) {
  console.log('  OK No hardcoded English strings found in .astro components');
} else {
  console.log(`  !! Found ${hardcoded.length} potential hardcoded English strings:`);
  for (const h of hardcoded.slice(0, 30)) {
    console.log(`     ${h.file}:${h.line} -- "${h.text}"`);
  }
}

// Also check the EDDI Manager translations
console.log('\n\n=== EDDI Manager Translation Audit ===\n');
const managerLocalesDir = 'c:/dev/git/EDDI-Manager/src/i18n/locales';
const managerEnContent = fs.readFileSync(path.join(managerLocalesDir, 'en.json'), 'utf-8');
const managerEn = JSON.parse(managerEnContent);

function flattenKeys(obj, prefix = '') {
  const keys = [];
  for (const [key, value] of Object.entries(obj)) {
    const fullKey = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      keys.push(...flattenKeys(value, fullKey));
    } else {
      keys.push(fullKey);
    }
  }
  return keys;
}

const managerEnKeys = flattenKeys(managerEn);
console.log(`English (source of truth): ${managerEnKeys.length} keys\n`);

console.log('--- File Sizes ---');
const managerFiles = fs.readdirSync(managerLocalesDir).filter(f => f.endsWith('.json'));
for (const file of managerFiles) {
  const stats = fs.statSync(path.join(managerLocalesDir, file));
  console.log(`  ${file}: ${stats.size.toLocaleString()} bytes`);
}

console.log('\n--- Key Comparison ---');
for (const file of managerFiles) {
  if (file === 'en.json') continue;
  const locale = file.replace('.json', '');
  const content = JSON.parse(fs.readFileSync(path.join(managerLocalesDir, file), 'utf-8'));
  const keys = flattenKeys(content);
  
  const enSet = new Set(managerEnKeys);
  const localeSet = new Set(keys);
  
  const missing = managerEnKeys.filter(k => !localeSet.has(k));
  const extra = keys.filter(k => !enSet.has(k));
  
  if (missing.length === 0 && extra.length === 0) {
    console.log(`  OK ${locale}: All ${keys.length} keys match English`);
  } else {
    console.log(`  !! ${locale}: ${keys.length} keys (${missing.length} missing, ${extra.length} extra)`);
    if (missing.length > 0) {
      console.log(`     Missing: ${missing.slice(0, 20).join(', ')}${missing.length > 20 ? ` ... and ${missing.length - 20} more` : ''}`);
    }
    if (extra.length > 0) {
      console.log(`     Extra: ${extra.slice(0, 10).join(', ')}${extra.length > 10 ? ` ... and ${extra.length - 10} more` : ''}`);
    }
  }
}

// Check for untranslated values in Manager
console.log('\n--- Checking for Untranslated Strings (Manager) ---');

function flattenValues(obj, prefix = '') {
  const entries = [];
  for (const [key, value] of Object.entries(obj)) {
    const fullKey = prefix ? `${prefix}.${key}` : key;
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      entries.push(...flattenValues(value, fullKey));
    } else if (typeof value === 'string' && value.length > 5) {
      entries.push([fullKey, value]);
    }
  }
  return entries;
}

const managerEnValues = Object.fromEntries(flattenValues(managerEn));

for (const file of managerFiles) {
  if (file === 'en.json') continue;
  const locale = file.replace('.json', '');
  const content = JSON.parse(fs.readFileSync(path.join(managerLocalesDir, file), 'utf-8'));
  const localeValues = Object.fromEntries(flattenValues(content));
  
  const untranslated = [];
  for (const [key, value] of Object.entries(localeValues)) {
    if (managerEnValues[key] && value === managerEnValues[key] &&
        !value.includes('EDDI') && !value.includes('http') && !value.includes('JSON') &&
        !value.includes('API') && !value.includes('MCP') && !value.includes('LLM') &&
        !value.includes('MongoDB') && !value.includes('PostgreSQL')) {
      untranslated.push(key);
    }
  }
  
  if (untranslated.length === 0) {
    console.log(`  OK ${locale}: No untranslated strings detected`);
  } else {
    console.log(`  !! ${locale}: ${untranslated.length} potentially untranslated strings`);
    console.log(`     Keys: ${untranslated.slice(0, 20).join(', ')}${untranslated.length > 20 ? ` ... and ${untranslated.length - 20} more` : ''}`);
  }
}

console.log('\n=== Audit Complete ===');
