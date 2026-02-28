# Kleenr

A privacy-focused text cleaning utility that runs entirely in your browser. No data ever leaves your machine.

## Features

- **Whitespace**: Remove extra spaces, trim lines, fix line breaks, join paragraphs
- **Indentation**: Convert tabs/spaces, adjust indent levels
- **Case**: Upper, lower, title, sentence, and random case
- **Quotes**: Convert between straight/curly, single/double quotes
- **Lines**: Sort, reverse, deduplicate, number lines
- **Encoding**: HTML to plain text, URL encode/decode, ROT13
- **Regex**: Full find-and-replace with preset patterns

## Usage

Open `index.html` in a browser. No build step or server required.

## Tech

Single-file HTML app using [Scittle](https://github.com/babashka/scittle) (browser-based ClojureScript) with Reagent and React 18.

## License

[MIT](LICENSE)
