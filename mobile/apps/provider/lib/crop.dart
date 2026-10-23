import 'dart:typed_data';

import 'package:crop_your_image/crop_your_image.dart';
import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

/// Lets the owner cut a picture to the wanted shape before it is uploaded. Returns the cut picture, or null when they go back.
/// "Use the whole picture" returns the original bytes.
Future<Uint8List?> cropPicture(BuildContext context, Uint8List bytes, {double aspect = 1}) =>
    Navigator.of(context).push<Uint8List>(MaterialPageRoute(fullscreenDialog: true, builder: (_) => _CropPage(bytes: bytes, aspect: aspect)));

class _CropPage extends StatefulWidget {
  const _CropPage({required this.bytes, required this.aspect});
  final Uint8List bytes;
  final double aspect;
  @override
  State<_CropPage> createState() => _CropPageState();
}

class _CropPageState extends State<_CropPage> {
  final _controller = CropController();
  late double _ratio = widget.aspect;
  bool _busy = false;

  static const _ratios = [('1:1', 1.0), ('4:3', 4 / 3), ('3:4', 3 / 4), ('16:9', 16 / 9)];

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    return Scaffold(
      appBar: AppBar(title: Text(ar ? 'قص الصورة' : 'Crop the picture')),
      body: SafeArea(child: Column(children: [
        Expanded(child: Crop(
          image: widget.bytes,
          controller: _controller,
          aspectRatio: _ratio,
          baseColor: Colors.black,
          maskColor: Colors.black.withValues(alpha: 0.55),
          onCropped: (result) {
            switch (result) {
              case CropSuccess(:final croppedImage):
                Navigator.of(context).pop(croppedImage);
              case CropFailure():
                if (mounted) setState(() => _busy = false);
                toast(context, ar ? 'تعذّر قص الصورة. جرّب صورة أخرى.' : 'Could not crop this picture. Try another one.', isError: true);
            }
          },
        )),
        Padding(padding: const EdgeInsets.fromLTRB(12, 10, 12, 0), child: Wrap(spacing: 8, children: [
          for (final r in _ratios) ChoiceChip(label: Text(r.$1), selected: (_ratio - r.$2).abs() < 0.01, onSelected: (_) => setState(() { _ratio = r.$2; _controller.aspectRatio = r.$2; })),
        ])),
        Padding(padding: const EdgeInsets.all(14), child: Row(children: [
          Expanded(child: OutlinedButton(onPressed: _busy ? null : () => Navigator.of(context).pop(widget.bytes), child: Text(ar ? 'الصورة كاملة' : 'Whole picture'))),
          const SizedBox(width: 10),
          Expanded(child: FilledButton(onPressed: _busy ? null : () { setState(() => _busy = true); _controller.crop(); }, child: _busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : Text(ar ? 'استخدم هذا القص' : 'Use this crop'))),
        ])),
      ])),
    );
  }
}
