import { CSSProperties } from 'react';
import {
  GREEN_COLOR,
  LIGHT_GREY_BORDER,
  LIGHT_GREY_COLOR,
} from '../../../../../styles/DefaultStylingProperties';

const styles: CSSProperties = {
  button: {
    ':hover': {
      backgroundColor: '#666666',
    },
    position: 'relative',
    top: '2px',
    border: LIGHT_GREY_BORDER,
    width: '21px',
    height: '21px',
    borderRadius: '3px',
    backgroundColor: '#555555',
    marginLeft: '4px',
    color: LIGHT_GREY_COLOR,
    cursor: 'pointer',
  },
  validateButton: {
    ':hover': {
      backgroundColor: '#75d79f',
    },
    position: 'relative',
    display: 'flex',
    top: '2px',
    border: LIGHT_GREY_BORDER,
    height: '21px',
    fontSize: '15px',
    textShadow: '-1px 0 #414141, 0 1px #414141, 1px 0 #414141, 0 -1px #414141',
    paddingRight: '3px',
    paddingLeft: '3px',
    borderRadius: '3px',
    lineHeight: '21px',
    backgroundColor: GREEN_COLOR,
    marginLeft: '4px',
    color: LIGHT_GREY_COLOR,
    cursor: 'pointer',
  },
  icon: {
    display: 'table',
    height: '15px',
    width: '15px',
    marginTop: '3px',
    marginLeft: 'auto',
    marginRight: 'auto',
  },
};

export default styles;
