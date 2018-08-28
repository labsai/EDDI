import { CSSProperties } from 'react';

const styles: CSSProperties = {
  pluginCenter: {
    flex: '1',
  },
  pluginContainer: {
    display: 'inline-block',
    minWidth: '100%',
  },
  pluginWithExtensionsContainer: {
    display: 'inline-block',
    minWidth: '100%',
    marginTop: '10px',
  },
  extensionContainer: {
    display: 'inline-block',
  },
  updateAvailableBorderColor: {
    border: '1px solid #FF5976',
  },
  updateAvailableTextColor: {
    color: '#FF5976',
  },
  pluginBox: {
    backgroundColor: '#f4f6f9',
    border: '1px solid #d8dde6',
    borderRadius: '4px',
    cursor: 'pointer',
    outline: 'none',
    padding: '0px',
    width: '100%',
    height: '80px',
    marginTop: '-5px',
  },
  pluginBoxWithExtensions: {
    backgroundColor: '#f4f6f9',
    border: '1px solid #d8dde6',
    borderRadius: '4px',
    cursor: 'pointer',
    outline: 'none',
    padding: '0px',
    width: '100%',
    marginTop: '-5px',
  },
  extensionBox: {
    backgroundColor: '#FFF',
    border: '1px solid #d8dde6',
    borderRadius: '4px',
    cursor: 'pointer',
    outline: 'none',
    padding: '0px',
    width: '100%',
    height: '80px',
    marginTop: '-5px',
  },
  bigPluginName: {
    display: 'flex',
    height: '22px',
    marginLeft: '5px',
    marginRight: '5px',
    marginTop: '15px',
  },
  centerFlex: {
    flex: '1',
  },
  addExtensionButton: {
    fontSize: '12px',
    textDecoration: 'underline',
    color: '#0070D2',
    marginTop: '5px',
  },
  addResourceButton: {
    position: 'relative',
    marginRight: '8px',
    marginLeft: 'auto',
    top: '-70px',
    fontSize: '12px',
    textDecoration: 'underline',
    textAlign: 'right',
    color: '#0070D2',
    width: 'fit-content',
  },
  pluginHeader: {
    display: 'flex',
    height: '22px',
    marginLeft: '5px',
    marginRight: '5px',
  },
  pluginName: {
    color: '#4a90e2',
    fontSize: '16px',
    overflow: 'hidden',
    textAlign: 'left',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  pluginVersion: {
    color: '#a8b7c7',
    fontSize: '12px',
    lineHeight: '21px',
    marginLeft: '2px',
    textAlign: 'left',
  },
  pluginDate: {
    color: '#54698d',
    fontSize: '12px',
    marginLeft: '5px',
    marginTop: '5px',
    textAlign: 'left',
  },
  updateAvailableButton: {
    ':hover': {
      boxShadow: '0 0 3px #0070d2',
    },
    backgroundColor: '#ffffff',
    border: '1px solid #d8dde6',
    borderRadius: '4px',
    color: '#0070d2',
    display: 'block',
    fontSize: '12px',
    height: '34px',
    lineHeight: '30px',
    marginLeft: 'auto',
    marginRight: '5px',
    marginTop: '-40px',
    position: 'relative',
    textAlign: 'center',
    whiteSpace: 'nowrap',
    width: '94px',
    cursor: 'pointer',
  },

  updateAvailablePluginNameAndVersion: {
    marginRight: '100px',
  },

  hasNewVersionBorder: {
    border: '1px solid #ff5976',
  },

  hasNewVersion: {
    color: '#ff5976',
  },

  closeButton: {
    ':focus': {
      color: '#FF5976',
      border: '1px solid #FF5976',
    },
    ':hover': {
      color: '#FF5976',
      border: '1px solid #FF5976',
    },
    backgroundColor: '#FFF',
    color: '#D8DDE6',
    cursor: 'pointer',
    fontSize: '25px',
    position: 'relative',
    border: '1px solid #D8DDE6',
    borderRadius: '4px',
    height: '23px',
    width: '23px',
    textAlign: 'center',
    lineHeight: '23px',
    marginLeft: 'auto',
    marginRight: '10px',
  },
};
export default styles;
