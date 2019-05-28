import { CSSProperties } from 'react';
import { LIGHT_GREY_COLOR } from '../../../../styles/DefaultStylingProperties';

const styles: CSSProperties = {
  expand: {
    position: 'fixed',
    height: '100%',
    width: '100%',
    zIndex: '0',
    top: '0',
    left: '0',
    marginBottom: '0px',
  },
  expandButton: {
    marginLeft: 'auto',
    marginRight: '4px',
  },
  editorUI: {
    width: '100%',
    height: '100%',
  },
  editorButtons: {
    display: 'flex',
    backgroundColor: '#414141',
    width: '100%',
    paddingBottom: '2px',
  },
  checkBoxText: {
    lineHeight: '26px',
    fontSize: '12px',
    marginLeft: '2px',
    color: LIGHT_GREY_COLOR,
  },
};

export default styles;
