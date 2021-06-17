import * as React from 'react';
import Plugin from './Plugin';
import * as _ from 'lodash';

interface IPluginsList {
  availablePlugins: string[];
  isPluginSelected: (pluginResource: string) => boolean;
  selectPlugin: (pluginResource: string) => void;
  selectVersion: (resource: string, version: number) => void;
}

const PluginsList: React.FunctionComponent<IPluginsList> = ({
  availablePlugins,
  selectPlugin,
  selectVersion,
  isPluginSelected,
}) => {
  return (
    !_.isEmpty(availablePlugins) && (
      <div>
        {availablePlugins.map((p, i) => (
          <Plugin
            key={i}
            selected={isPluginSelected(p)}
            pluginResource={p}
            handleClick={selectPlugin}
            selectVersion={selectVersion}
          />
        ))}
      </div>
    )
  );
};

export default PluginsList;
