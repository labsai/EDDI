import { CSSProperties } from 'react';

const styles: CSSProperties = {
  pluginCenter: {
    flex: '1',
  },
  pluginContainer: {
    display: 'inline-block',
    height: '72px',
    width: '100%',
    paddingTop: '10px',
  },
  removablePluginContainer: {
    display: 'inline-block',
    height: '72px',
    width: '100%',
    paddingTop: '10px',
  },
  updateAvailableBorderColor: {
    border: '1px solid #FF5976',
  },
  updateAvailableTextColor: {
    color: '#FF5976',
  },
  updateAvailableButton: {
    ':hover': {
      boxShadow: '0 0 3px #0070d2',
    },
    display: 'block',
    height: '34px',
    marginLeft: 'auto',
    marginRight: '5px',
    marginTop: '-60px',
    position: 'relative',
    top: '-3px',
    whiteSpace: 'nowrap',
    width: '94px',
  },
};
export default styles;
