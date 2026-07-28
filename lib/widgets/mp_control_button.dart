import 'package:flutter/material.dart';

class ControlButton extends StatelessWidget {
  final IconData? icon;
  final VoidCallback? onPressed;
  const ControlButton(this.icon, this.onPressed);

  @override
  Widget build(BuildContext context) {
    return IconButton(
      icon: Icon(icon),
      onPressed: onPressed,
      color: Theme.of(context).colorScheme.primary,
    );
  }
}
