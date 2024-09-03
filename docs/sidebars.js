const sidebars = {
  sidebar: [
    {
      type: "category",
      label: "ZIO Google Cloud Pub/Sub",
      collapsed: false,
      link: { type: "doc", id: "index" },
      items: [
        "installation",
        "subscriber",
        "publisher",
        "serde",
        "admin"
      ]
    }
  ]
};

module.exports = sidebars;
