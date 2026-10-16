def layer_tree_insert_index(child_count, overlay):
    """Return the QGIS layer-tree index for an overlay or opaque basemap."""
    return 0 if overlay else child_count
